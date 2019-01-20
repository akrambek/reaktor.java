/**
 * Copyright 2016-2018 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.reaktor.internal.agent;

import static java.lang.Long.bitCount;
import static java.lang.Long.numberOfTrailingZeros;
import static java.lang.String.format;
import static java.lang.ThreadLocal.withInitial;
import static java.util.Objects.requireNonNull;
import static org.agrona.CloseHelper.quietClose;
import static org.agrona.LangUtil.rethrowUnchecked;
import static org.reaktivity.reaktor.internal.router.RouteId.localId;
import static org.reaktivity.reaktor.internal.router.RouteId.remoteId;
import static org.reaktivity.reaktor.internal.router.StreamId.remoteIndex;
import static org.reaktivity.reaktor.internal.router.StreamId.replyToIndex;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.ArrayUtil;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.RingBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.reaktivity.nukleus.Elektron;
import org.reaktivity.nukleus.Nukleus;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.route.RouteKind;
import org.reaktivity.nukleus.stream.StreamFactory;
import org.reaktivity.nukleus.stream.StreamFactoryBuilder;
import org.reaktivity.reaktor.internal.Counters;
import org.reaktivity.reaktor.internal.LabelManager;
import org.reaktivity.reaktor.internal.ReaktorConfiguration;
import org.reaktivity.reaktor.internal.buffer.CountingBufferPool;
import org.reaktivity.reaktor.internal.buffer.DefaultBufferPool;
import org.reaktivity.reaktor.internal.layouts.StreamsLayout;
import org.reaktivity.reaktor.internal.router.GroupBudgetManager;
import org.reaktivity.reaktor.internal.router.Resolver;
import org.reaktivity.reaktor.internal.router.Router;
import org.reaktivity.reaktor.internal.router.Target;
import org.reaktivity.reaktor.internal.router.WriteCounters;
import org.reaktivity.reaktor.internal.types.stream.AbortFW;
import org.reaktivity.reaktor.internal.types.stream.BeginFW;
import org.reaktivity.reaktor.internal.types.stream.DataFW;
import org.reaktivity.reaktor.internal.types.stream.EndFW;
import org.reaktivity.reaktor.internal.types.stream.FrameFW;
import org.reaktivity.reaktor.internal.types.stream.ResetFW;
import org.reaktivity.reaktor.internal.types.stream.SignalFW;
import org.reaktivity.reaktor.internal.types.stream.WindowFW;

public class ElektronAgent implements Agent
{
    private final ThreadLocal<SignalFW.Builder> signalRW = withInitial(ElektronAgent::newSignalRW);

    private final FrameFW frameRO = new FrameFW();
    private final BeginFW beginRO = new BeginFW();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();

    private final int localIndex;
    private final ReaktorConfiguration config;
    private final LabelManager labels;
    private final ExecutorService executor;
    private final ToLongFunction<String> affinityMask;
    private final String elektronName;
    private final Counters counters;
    private final NukleusAgent nukleusAgent;
    private final boolean timestamps;
    private final StreamsLayout streamsLayout;
    private final RingBuffer streamsBuffer;
    private final MutableDirectBuffer writeBuffer;
    private final Long2ObjectHashMap<MessageConsumer> streams;
    private final Long2ObjectHashMap<MessageConsumer> throttles;
    private final Long2ObjectHashMap<ReadCounters> countersByRouteId;
    private final Int2ObjectHashMap<MessageConsumer> writersByIndex;
    private final Int2ObjectHashMap<Target> targetsByIndex;
    private final Map<String, ElektronRef> elektronByName;
    private final BufferPool bufferPool;
    private final long mask;
    private final MessageHandler readHandler;
    private final int readLimit;
    private final GroupBudgetManager groupBudgetManager;

    private final Resolver resolver;

    // TODO: copy-on-write
    private final Int2ObjectHashMap<StreamFactory> streamFactoriesByLabelId;

    private final Long2LongHashMap affinityByRemoteId;

    private long streamId;
    private long correlationId;
    private long traceId;
    private long groupId;

    private volatile Agent[] agents;

    public ElektronAgent(
        int index,
        int count,
        ReaktorConfiguration config,
        LabelManager labels,
        ExecutorService executor,
        ToLongFunction<String> affinityMask,
        Counters counters,
        NukleusAgent nukleusAgent)
    {
        this.localIndex = index;
        this.config = config;
        this.labels = labels;
        this.executor = executor;
        this.affinityMask = affinityMask;

        final StreamsLayout streamsLayout = new StreamsLayout.Builder()
            .path(config.directory().resolve(String.format("data%d", index)))
            .streamsCapacity(config.streamsBufferCapacity())
            .readonly(false)
            .build();
        this.streamsLayout = streamsLayout;
        this.groupBudgetManager = new GroupBudgetManager();

        this.elektronName = String.format("reaktor/data#%d", index);
        this.counters = counters;
        this.nukleusAgent = nukleusAgent;
        this.timestamps = config.timestamps();
        this.readLimit = config.maximumMessagesPerRead();
        this.streamsBuffer = streamsLayout.streamsBuffer();
        this.writeBuffer = new UnsafeBuffer(new byte[streamsBuffer.maxMsgLength()]);
        this.streams = new Long2ObjectHashMap<>();
        this.throttles = new Long2ObjectHashMap<>();
        this.countersByRouteId = new Long2ObjectHashMap<>();
        this.streamFactoriesByLabelId = new Int2ObjectHashMap<>();
        this.readHandler = this::handleRead;
        this.elektronByName = new ConcurrentHashMap<>();
        this.affinityByRemoteId = new Long2LongHashMap(-1L);
        this.targetsByIndex = new Int2ObjectHashMap<>();
        this.writersByIndex = new Int2ObjectHashMap<>();
        this.agents = new Agent[0];

        final Router router = nukleusAgent.router();
        this.resolver = router.newResolver(throttles, this::supplyInitialWriter);

        final int bufferPoolCapacity = config.bufferPoolCapacity();
        final int bufferSlotCapacity = config.bufferSlotCapacity();
        final BufferPool bufferPool = new DefaultBufferPool(bufferPoolCapacity, bufferSlotCapacity);

        final int reserved = Byte.SIZE; // includes reply bit
        final int bits = Long.SIZE - reserved;
        final long initial = ((long) index) << bits;
        final long mask = initial | (-1L >>> reserved);

        assert mask >= 0; // high bit used by reply id

        this.mask = mask;
        this.bufferPool = bufferPool;
        this.streamId = initial;
        this.correlationId = initial;
        this.traceId = initial;
        this.groupId = initial;
    }

    @Override
    public String roleName()
    {
        return elektronName;
    }

    @Override
    public int doWork() throws Exception
    {
        int workDone = 0;

        for (final Agent agent : agents)
        {
            workDone += agent.doWork();
        }

        workDone += streamsBuffer.read(readHandler, readLimit);

        return workDone;
    }

    @Override
    public void onClose()
    {
        for (final Agent agent : agents)
        {
            agent.onClose();
        }

        streams.forEach(this::doSyntheticAbort);

        targetsByIndex.forEach((k, v) -> v.detach());
        targetsByIndex.forEach((k, v) -> quietClose(v));

        quietClose(streamsLayout);

        if (bufferPool.acquiredSlots() != 0)
        {
            throw new IllegalStateException("Buffer pool has unreleased slots: " + bufferPool.acquiredSlots());
        }
    }

    @Override
    public String toString()
    {
        return elektronName;
    }

    public void onRouteable(
        long routeId,
        Nukleus nukleus)
    {
        String nukleusName = nukleus.name();
        int localAddressId = localId(routeId);
        String localAddress = labels.lookupLabel(localAddressId);
        long affinity = affinityMask.applyAsLong(localAddress);
        if ((affinity & (1L << localIndex)) != 0L)
        {
            elektronByName.computeIfAbsent(nukleusName, name -> new ElektronRef(name, nukleus.supplyElektron()));
        }
    }

    public void onRouted(
        Nukleus nukleus,
        RouteKind routeKind,
        long routeId)
    {
        String nukleusName = nukleus.name();
        int localAddressId = localId(routeId);
        String localAddress = labels.lookupLabel(localAddressId);
        long affinity = affinityMask.applyAsLong(localAddress);
        if ((affinity & (1L << localIndex)) != 0L)
        {
            elektronByName.computeIfPresent(nukleusName, (a, r) -> r.assign(routeKind, localAddressId));
        }
    }

    public void onUnrouted(
        Nukleus nukleus,
        RouteKind routeKind,
        long routeId)
    {
        String nukleusName = nukleus.name();
        int localAddressId = localId(routeId);
        String localAddress = labels.lookupLabel(localAddressId);
        long affinity = affinityMask.applyAsLong(localAddress);
        if ((affinity & (1L << localIndex)) != 0L)
        {
            elektronByName.computeIfPresent(nukleusName, (a, r) -> r.unassign(routeKind, localAddressId));
        }
    }

    private void handleRead(
        int msgTypeId,
        MutableDirectBuffer buffer,
        int index,
        int length)
    {
        final FrameFW frame = frameRO.wrap(buffer, index, index + length);
        final long streamId = frame.streamId();
        final long routeId = frame.routeId();

        try
        {
            if ((streamId & 0x8000_0000_0000_0000L) == 0L)
            {
                handleReadInitial(routeId, streamId, msgTypeId, buffer, index, length);
            }
            else
            {
                handleReadReply(routeId, streamId, msgTypeId, buffer, index, length);
            }
        }
        catch (Throwable ex)
        {
            ex.addSuppressed(new Exception(String.format("[%s]\t[0x%016x] %s",
                                                         elektronName, streamId, streamsLayout)));
            rethrowUnchecked(ex);
        }
    }

    private void handleReadInitial(
        long routeId,
        long streamId,
        int msgTypeId,
        MutableDirectBuffer buffer,
        int index,
        int length)
    {
        if ((msgTypeId & 0x4000_0000) == 0)
        {
            final MessageConsumer handler = streams.get(streamId);
            if (handler != null)
            {
                switch (msgTypeId)
                {
                case BeginFW.TYPE_ID:
                    handler.accept(msgTypeId, buffer, index, length);
                    break;
                case DataFW.TYPE_ID:
                    handler.accept(msgTypeId, buffer, index, length);
                    break;
                case EndFW.TYPE_ID:
                    handler.accept(msgTypeId, buffer, index, length);
                    streams.remove(streamId);
                    break;
                case AbortFW.TYPE_ID:
                    handler.accept(msgTypeId, buffer, index, length);
                    streams.remove(streamId);
                    break;
                case SignalFW.TYPE_ID:
                    handler.accept(msgTypeId, buffer, index, length);
                    break;
                default:
                    doReset(routeId, streamId);
                    break;
                }
            }
            else if (msgTypeId == BeginFW.TYPE_ID)
            {
                final MessageConsumer newHandler = handleBeginInitial(msgTypeId, buffer, index, length);
                if (newHandler != null)
                {
                    newHandler.accept(msgTypeId, buffer, index, length);
                }
                else
                {
                    doReset(routeId, streamId);
                }
            }
        }
        else
        {
            final MessageConsumer throttle = throttles.get(streamId);
            if (throttle != null)
            {
                final ReadCounters counters = countersByRouteId.computeIfAbsent(routeId, this::newReadCounters);
                switch (msgTypeId)
                {
                case WindowFW.TYPE_ID:
                    counters.windows.increment();
                    throttle.accept(msgTypeId, buffer, index, length);
                    break;
                case ResetFW.TYPE_ID:
                    counters.resets.increment();
                    throttle.accept(msgTypeId, buffer, index, length);
                    throttles.remove(streamId);
                    break;
                default:
                    break;
                }
            }
        }
    }

    private void handleReadReply(
        long routeId,
        long streamId,
        int msgTypeId,
        MutableDirectBuffer buffer,
        int index,
        int length)
    {
        if ((msgTypeId & 0x4000_0000) == 0)
        {
            final MessageConsumer handler = streams.get(streamId);
            if (handler != null)
            {
                final ReadCounters counters = countersByRouteId.computeIfAbsent(routeId, this::newReadCounters);
                switch (msgTypeId)
                {
                case BeginFW.TYPE_ID:
                    counters.opens.increment();
                    handler.accept(msgTypeId, buffer, index, length);
                    break;
                case DataFW.TYPE_ID:
                    counters.frames.increment();
                    counters.bytes.add(buffer.getInt(index + DataFW.FIELD_OFFSET_LENGTH));
                    handler.accept(msgTypeId, buffer, index, length);
                    break;
                case EndFW.TYPE_ID:
                    counters.closes.increment();
                    handler.accept(msgTypeId, buffer, index, length);
                    streams.remove(streamId);
                    break;
                case AbortFW.TYPE_ID:
                    counters.aborts.increment();
                    handler.accept(msgTypeId, buffer, index, length);
                    streams.remove(streamId);
                    break;
                case SignalFW.TYPE_ID:
                    handler.accept(msgTypeId, buffer, index, length);
                    break;
                default:
                    doReset(routeId, streamId);
                    break;
                }
            }
            else if (msgTypeId == BeginFW.TYPE_ID)
            {
                final MessageConsumer newHandler = handleBeginReply(msgTypeId, buffer, index, length);
                if (newHandler != null)
                {
                    final ReadCounters counters = countersByRouteId.computeIfAbsent(routeId, this::newReadCounters);
                    counters.opens.increment();
                    newHandler.accept(msgTypeId, buffer, index, length);
                }
                else
                {
                    doReset(routeId, streamId);
                }
            }
        }
        else
        {
            final MessageConsumer throttle = throttles.get(streamId);
            if (throttle != null)
            {
                switch (msgTypeId)
                {
                case WindowFW.TYPE_ID:
                    throttle.accept(msgTypeId, buffer, index, length);
                    break;
                case ResetFW.TYPE_ID:
                    throttle.accept(msgTypeId, buffer, index, length);
                    throttles.remove(streamId);
                    break;
                default:
                    break;
                }
            }
        }
    }

    private MessageConsumer handleBeginInitial(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long routeId = begin.routeId();
        final long streamId = begin.streamId();
        final int labelId = remoteId(routeId);

        MessageConsumer newStream = null;

        final StreamFactory streamFactory = streamFactoriesByLabelId.get(labelId);
        if (streamFactory != null)
        {
            final MessageConsumer replyTo = supplyReplyTo(streamId);
            newStream = streamFactory.newStream(msgTypeId, buffer, index, length, replyTo);
            if (newStream != null)
            {
                streams.put(streamId, newStream);
            }
        }

        return newStream;
    }

    private MessageConsumer handleBeginReply(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long routeId = begin.routeId();
        final long streamId = begin.streamId();
        final int labelId = localId(routeId);

        MessageConsumer newStream = null;

        final StreamFactory streamFactory = streamFactoriesByLabelId.get(labelId);
        if (streamFactory != null)
        {
            final MessageConsumer replyTo = supplyReplyTo(streamId);
            newStream = streamFactory.newStream(msgTypeId, buffer, index, length, replyTo);
            if (newStream != null)
            {
                streams.put(streamId, newStream);
            }
        }

        return newStream;
    }

    private void doReset(
        final long routeId,
        final long streamId)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .build();

        final MessageConsumer replyTo = supplyReplyTo(streamId);
        replyTo.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }

    private void doSyntheticAbort(
        long streamId,
        MessageConsumer stream)
    {
        final long syntheticAbortRouteId = 0L;

        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                     .routeId(syntheticAbortRouteId)
                                     .streamId(streamId)
                                     .build();

        stream.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private MessageConsumer supplyReplyTo(
        long streamId)
    {
        final int index = replyToIndex(streamId);
        return writersByIndex.computeIfAbsent(index, this::supplyWriter);
    }

    private MessageConsumer supplyInitialWriter(
        long streamId)
    {
        final int index = remoteIndex(streamId);
        return writersByIndex.computeIfAbsent(index, this::supplyWriter);
    }

    private MessageConsumer supplyWriter(
        int index)
    {
        return supplyTarget(index).writeHandler();
    }

    private Target supplyTarget(
        int index)
    {
        return targetsByIndex.computeIfAbsent(index, this::newTarget);
    }

    private Target newTarget(
        int index)
    {
        return new Target(config, index, writeBuffer, streams, throttles, this::newWriteCounters);
    }

    private ReadCounters newReadCounters(
        long routeId)
    {
        final int localId = localId(routeId);
        final String nukleus = nukleusAgent.nukleus(localId);
        return new ReadCounters(counters, nukleus, routeId);
    }

    private WriteCounters newWriteCounters(
        long routeId)
    {
        final int localId = localId(routeId);
        final String nukleus = nukleusAgent.nukleus(localId);
        return new WriteCounters(counters, nukleus, routeId);
    }

    private static final class ReadCounters
    {
        private final AtomicCounter opens;
        private final AtomicCounter closes;
        private final AtomicCounter aborts;
        private final AtomicCounter windows;
        private final AtomicCounter resets;
        private final AtomicCounter bytes;
        private final AtomicCounter frames;

        ReadCounters(
            Counters counters,
            String nukleus,
            long routeId)
        {
            this.opens = counters.counter(format("%s.%d.opens.read", nukleus, routeId));
            this.closes = counters.counter(format("%s.%d.closes.read", nukleus, routeId));
            this.aborts = counters.counter(format("%s.%d.aborts.read", nukleus, routeId));
            this.windows = counters.counter(format("%s.%d.windows.read", nukleus, routeId));
            this.resets = counters.counter(format("%s.%d.resets.read", nukleus, routeId));
            this.bytes = counters.counter(format("%s.%d.bytes.read", nukleus, routeId));
            this.frames = counters.counter(format("%s.%d.frames.read", nukleus, routeId));
        }
    }

    private final class ElektronRef
    {
        private final Elektron elektron;
        private final Map<RouteKind, StreamFactory> streamFactories;

        private int count;

        private ElektronRef(
            String nukleusName,
            Elektron elekron)
        {
            this.elektron = requireNonNull(elekron);

            final Map<RouteKind, StreamFactory> streamFactories = new EnumMap<>(RouteKind.class);
            final Map<String, AtomicCounter> countersByName = new HashMap<>();
            final Function<String, AtomicCounter> newCounter = counters::counter;
            final Function<String, LongSupplier> supplyCounter =
                    name -> () -> countersByName.computeIfAbsent(name, newCounter).increment() + 1;
            final Function<String, LongConsumer> supplyAccumulator = name -> inc -> counters.counter(name).add(inc);
            final AtomicCounter acquires = counters.counter(String.format("%s.acquires", nukleusName));
            final AtomicCounter releases = counters.counter(String.format("%s.releases", nukleusName));
            final BufferPool countingPool = new CountingBufferPool(bufferPool, acquires::increment, releases::increment);
            final Supplier<BufferPool> supplyCountingBufferPool = () -> countingPool;

            for (RouteKind routeKind : EnumSet.allOf(RouteKind.class))
            {
                final StreamFactoryBuilder streamFactoryBuilder = elektron.streamFactoryBuilder(routeKind);
                if (streamFactoryBuilder != null)
                {
                    StreamFactory streamFactory = streamFactoryBuilder
                            .setRouteManager(resolver)
                            .setExecutor(this::executeAndSignal)
                            .setWriteBuffer(writeBuffer)
                            .setInitialIdSupplier(this::supplyInitialId)
                            .setReplyIdSupplier(this::supplyReplyId)
                            .setSourceCorrelationIdSupplier(this::supplyCorrelationId)
                            .setTargetCorrelationIdSupplier(this::supplyCorrelationId)
                            .setTraceSupplier(this::supplyTrace)
                            .setGroupIdSupplier(this::supplyGroupId)
                            .setGroupBudgetClaimer(groupBudgetManager::claim)
                            .setGroupBudgetReleaser(groupBudgetManager::release)
                            .setCounterSupplier(supplyCounter)
                            .setAccumulatorSupplier(supplyAccumulator)
                            .setBufferPoolSupplier(supplyCountingBufferPool)
                            .build();
                    streamFactories.put(routeKind, streamFactory);
                }
            }
            this.streamFactories = streamFactories;
        }

        public ElektronRef assign(
            RouteKind routeKind,
            int labelId)
        {
            synchronized (this)
            {
                if (this.count == 0)
                {
                    final Agent agent = elektron.agent();
                    if (agent != null)
                    {
                        agents = ArrayUtil.add(agents, agent);
                    }
                }

                final StreamFactory streamFactory = streamFactories.get(routeKind);
                if (streamFactory != null)
                {
                    streamFactoriesByLabelId.put(labelId, streamFactory);
                }
                this.count++;
            }

            return this;
        }

        public ElektronRef unassign(
            RouteKind routeKind,
            int labelId)
        {
            synchronized (this)
            {
                this.count--;

                if (this.count == 0)
                {
                    final StreamFactory streamFactory = streamFactoriesByLabelId.remove(labelId);
                    assert streamFactory == streamFactories.get(routeKind);

                    final Agent agent = elektron.agent();
                    if (agent != null)
                    {
                        // TODO: quiesce streams first
                        agents = ArrayUtil.remove(agents, agent);
                        final Agent closeAgent = new Agent()
                        {

                            @Override
                            public int doWork() throws Exception
                            {
                                quietClose(agent::onClose);
                                agents = ArrayUtil.remove(agents, this);
                                return 1;
                            }

                            @Override
                            public String roleName()
                            {
                                return String.format("%s (deferred close)", agent.roleName());
                            }
                        };
                        agents = ArrayUtil.add(agents, closeAgent);
                    }
                }
            }

            return this;
        }

        public long supplyInitialId(
            long routeId)
        {
            final int remoteId = remoteId(routeId);
            final int remoteIndex = lookupTargetIndex(remoteId);

            streamId++;
            streamId &= mask;

            return (((long)remoteIndex << 48) & 0x00ff_0000_0000_0000L) |
                   (streamId & 0xff00_ffff_ffff_ffffL);
        }

        public long supplyReplyId(
            long initialId)
        {
            assert (initialId & 0x8000_0000_0000_0000L) == 0L;
            return initialId | 0x8000_0000_0000_0000L;
        }

        public long supplyGroupId()
        {
            groupId++;
            groupId &= mask;
            return groupId;
        }

        public long supplyTrace()
        {
            traceId++;
            traceId &= mask;
            return traceId;
        }

        public long supplyCorrelationId()
        {
            correlationId++;
            correlationId &= mask;
            return correlationId;
        }

        private Future<?> executeAndSignal(
            Runnable task,
            long routeId,
            long streamId,
            long signalId)
        {
            if (executor != null)
            {
                return executor.submit(() -> invokeAndSignal(task, routeId, streamId, signalId));
            }
            else
            {
                invokeAndSignal(task, routeId, streamId, signalId);
                return new Future<Void>()
                {
                    @Override
                    public boolean cancel(
                        boolean mayInterruptIfRunning)
                    {
                        return false;
                    }

                    @Override
                    public boolean isCancelled()
                    {
                        return false;
                    }

                    @Override
                    public boolean isDone()
                    {
                        return true;
                    }

                    @Override
                    public Void get() throws InterruptedException, ExecutionException
                    {
                        return null;
                    }

                    @Override
                    public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
                    {
                        return null;
                    }
                };
            }
        }

        private void invokeAndSignal(
            Runnable task,
            long routeId,
            long streamId,
            long signalId)
        {
            try
            {
                task.run();
            }
            finally
            {
                final long timestamp = timestamps ? System.nanoTime() : 0L;

                final SignalFW signal = signalRW.get()
                                                .rewrap()
                                                .routeId(routeId)
                                                .streamId(streamId)
                                                .timestamp(timestamp)
                                                .signalId(signalId)
                                                .build();

                streamsBuffer.write(signal.typeId(), signal.buffer(), signal.offset(), signal.sizeof());
            }
        }
    }

    private int lookupTargetIndex(
        int remoteId)
    {
        final long affinityMask = supplyAffinity(remoteId);
        return numberOfTrailingZeros(affinityMask);
    }

    private long supplyAffinity(
        int remoteId)
    {
        return affinityByRemoteId.computeIfAbsent(remoteId, this::resolveAffinity);
    }

    public long resolveAffinity(
        long remoteIdAsLong)
    {
        final int remoteId = (int)(remoteIdAsLong & 0xffff_ffffL);
        String remoteAddress = labels.lookupLabel(remoteId);

        long mask = affinityMask.applyAsLong(remoteAddress);

        if (bitCount(mask) != 1)
        {
            throw new IllegalStateException(String.format("affinity mask must specify exactly one bit: %s %d",
                    remoteAddress, mask));
        }

        return mask;
    }

    private static SignalFW.Builder newSignalRW()
    {
        MutableDirectBuffer buffer = new UnsafeBuffer(new byte[512]);
        return new SignalFW.Builder().wrap(buffer, 0, buffer.capacity());
    }
}