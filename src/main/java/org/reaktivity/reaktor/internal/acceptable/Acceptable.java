/**
 * Copyright 2016-2017 The Reaktivity Project
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
package org.reaktivity.reaktor.internal.acceptable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.reaktivity.nukleus.Nukleus;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessageFunction;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.route.RouteKind;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;
import org.reaktivity.nukleus.stream.StreamFactoryBuilder;
import org.reaktivity.reaktor.internal.Context;
import org.reaktivity.reaktor.internal.State;
import org.reaktivity.reaktor.internal.buffer.CountingBufferPool;
import org.reaktivity.reaktor.internal.layouts.StreamsLayout;
import org.reaktivity.reaktor.internal.router.ReferenceKind;
import org.reaktivity.reaktor.internal.router.Router;
import org.reaktivity.reaktor.internal.types.stream.AbortFW;
import org.reaktivity.reaktor.internal.types.stream.ResetFW;

public final class Acceptable extends Nukleus.Composite implements RouteManager
{
    private final AbortFW.Builder abortRW = new AbortFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();

    private final Context context;
    private final Router router;
    private final String sourceName;
    private final AtomicBuffer writeBuffer;
    private final Long2ObjectHashMap<MessageConsumer> streams;
    private final Map<String, Source> sourcesByName;
    private final Map<String, Target> targetsByName;
    private final Function<RouteKind, StreamFactory> supplyStreamFactory;
    private final boolean timestamps;

    public Acceptable(
        Context context,
        Router router,
        String sourceName,
        State state,
        LongFunction<IntUnaryOperator> groupBudgetClaimer,
        LongFunction<IntUnaryOperator> groupBudgetReleaser,
        Function<RouteKind, StreamFactoryBuilder> supplyStreamFactoryBuilder,
        boolean timestamps,
        AtomicLong correlations)
    {
        this.context = context;
        this.router = router;
        this.sourceName = sourceName;
        this.writeBuffer = new UnsafeBuffer(new byte[context.maxMessageLength()]);
        this.streams = new Long2ObjectHashMap<>();
        this.sourcesByName = new HashMap<>();
        this.targetsByName = new HashMap<>();

        final Map<RouteKind, StreamFactory> streamFactories = new EnumMap<>(RouteKind.class);
        final Function<String, LongSupplier> supplyCounter = name -> () -> context.counters().counter(name).increment() + 1;
        final Function<String, LongConsumer> supplyAccumulator = name -> (i) -> context.counters().counter(name).add(i);
        final AtomicCounter acquires = context.counters().acquires();
        final AtomicCounter releases = context.counters().releases();
        final BufferPool bufferPool = new CountingBufferPool(state.bufferPool(), acquires::increment, releases::increment);
        final Supplier<BufferPool> supplyCountingBufferPool = () -> bufferPool;
        for (RouteKind kind : EnumSet.allOf(RouteKind.class))
        {
            final ReferenceKind refKind = ReferenceKind.valueOf(kind);
            final LongSupplier supplyCorrelationId = () -> refKind.nextRef(correlations);
            final StreamFactoryBuilder streamFactoryBuilder = supplyStreamFactoryBuilder.apply(kind);
            if (streamFactoryBuilder != null)
            {
                StreamFactory streamFactory = streamFactoryBuilder
                        .setRouteManager(this)
                        .setWriteBuffer(writeBuffer)
                        .setStreamIdSupplier(state::supplyStreamId)
                        .setTraceSupplier(state::supplyTrace)
                        .setGroupIdSupplier(state::supplyGroupId)
                        .setGroupBudgetClaimer(groupBudgetClaimer)
                        .setGroupBudgetReleaser(groupBudgetReleaser)
                        .setCorrelationIdSupplier(supplyCorrelationId)
                        .setCounterSupplier(supplyCounter)
                        .setAccumulatorSupplier(supplyAccumulator)
                        .setBufferPoolSupplier(supplyCountingBufferPool)
                        .build();
                streamFactories.put(kind, streamFactory);
            }
        }
        this.supplyStreamFactory = streamFactories::get;
        this.timestamps = timestamps;
    }

    @Override
    public String name()
    {
        return sourceName;
    }

    @Override
    public void close() throws Exception
    {
        targetsByName.forEach(this::doAbort);
        sourcesByName.forEach(this::doReset);

        streams.forEach(this::doAbort);
        targetsByName.forEach(this::doReset);

        super.close();
    }

    public Source supplySource(
        String sourceName)
    {
        return sourcesByName.computeIfAbsent(sourceName, this::newSource);
    }

    @Override
    public <R> R resolve(
        long authorization,
        MessagePredicate filter,
        MessageFunction<R> mapper)
    {
        return router.resolve(authorization, filter, mapper);
    }

    @Override
    public void forEach(
        MessageConsumer consumer)
    {
        router.foreach(consumer);
    }

    @Override
    public MessageConsumer supplyTarget(
        String targetName)
    {
        return supplyTargetInternal(targetName).writeHandler();
    }

    @Override
    public void setThrottle(
        String targetName,
        long streamId,
        MessageConsumer throttle)
    {
        supplyTargetInternal(targetName).setThrottle(streamId, throttle);
    }

    private Source newSource(
        String partitionName)
    {
        StreamsLayout layout = new StreamsLayout.Builder()
            .path(context.sourceStreamsPath().apply(partitionName))
            .streamsCapacity(context.streamsBufferCapacity())
            .throttleCapacity(context.throttleBufferCapacity())
            .readonly(false)
            .build();

        return include(new Source(context.name(), partitionName, layout, writeBuffer, streams, supplyStreamFactory,
                                  timestamps));
    }

    private Target supplyTargetInternal(
        String targetName)
    {
        return targetsByName.computeIfAbsent(targetName, this::newTarget);
    }

    private Target newTarget(
        String targetName)
    {
        StreamsLayout layout = new StreamsLayout.Builder()
                .path(context.targetStreamsPath().apply(targetName))
                .streamsCapacity(context.streamsBufferCapacity())
                .throttleCapacity(context.throttleBufferCapacity())
                .readonly(true)
                .build();

        return include(new Target(targetName, layout, timestamps));
    }

    private void doAbort(
        String targetName,
        Target target)
    {
        target.abort();
    }

    private void doReset(
        String sourceName,
        Source source)
    {
        source.reset();
    }

    private void doAbort(
        long streamId,
        MessageConsumer stream)
    {
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                     .streamId(streamId)
                                     .build();

        stream.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doReset(
        String targetName,
        Target target)
    {
        target.reset(this::doReset);
    }

    private void doReset(
        long throttleId,
        MessageConsumer throttle)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                                     .streamId(throttleId)
                                     .build();

        throttle.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }
}
