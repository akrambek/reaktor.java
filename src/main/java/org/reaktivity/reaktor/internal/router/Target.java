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
package org.reaktivity.reaktor.internal.router;

import static org.reaktivity.reaktor.internal.types.stream.FrameFW.FIELD_OFFSET_TIMESTAMP;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.reaktor.internal.layouts.StreamsLayout;
import org.reaktivity.reaktor.internal.types.stream.AbortFW;
import org.reaktivity.reaktor.internal.types.stream.BeginFW;
import org.reaktivity.reaktor.internal.types.stream.DataFW;
import org.reaktivity.reaktor.internal.types.stream.EndFW;
import org.reaktivity.reaktor.internal.types.stream.FrameFW;
import org.reaktivity.reaktor.internal.types.stream.ResetFW;
import org.reaktivity.reaktor.internal.types.stream.WindowFW;

final class Target implements AutoCloseable
{
    private final FrameFW frameRO = new FrameFW();

    private final ResetFW.Builder resetRW = new ResetFW.Builder();

    private final String targetName;
    private final AutoCloseable layout;
    private final MutableDirectBuffer writeBuffer;
    private final boolean timestamps;
    private final Long2ObjectHashMap<MessageConsumer> streams;
    private final Long2ObjectHashMap<MessageConsumer> throttles;
    private final MessageConsumer writeHandler;

    private MessagePredicate streamsBuffer;

    Target(
        String targetName,
        StreamsLayout layout,
        MutableDirectBuffer writeBuffer,
        boolean timestamps,
        int maximumMessagesPerRead,
        Long2ObjectHashMap<MessageConsumer> streams,
        Long2ObjectHashMap<MessageConsumer> throttles)
    {
        this.targetName = targetName;
        this.layout = layout;
        this.writeBuffer = writeBuffer;
        this.timestamps = timestamps;
        this.streamsBuffer = layout.streamsBuffer()::write;
        this.streams = streams;
        this.throttles = throttles;
        this.writeHandler = this::handleWrite;
    }

    public void detach()
    {
        streamsBuffer = (t, b, i, l) -> true;
    }

    @Override
    public void close() throws Exception
    {
        throttles.forEach(this::doReset);

        layout.close();
    }

    @Override
    public String toString()
    {
        return String.format("%s (write)", targetName);
    }

    public void setThrottle(
        long streamId,
        MessageConsumer throttle)
    {
        throttles.put(streamId, throttle);
    }

    public MessageConsumer writeHandler()
    {
        return writeHandler;
    }

    private void handleWrite(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length)
    {
        boolean handled;

        if (timestamps)
        {
            ((MutableDirectBuffer) buffer).putLong(index + FIELD_OFFSET_TIMESTAMP, System.nanoTime());
        }

        switch (msgTypeId)
        {
        case BeginFW.TYPE_ID:
            handled = streamsBuffer.test(msgTypeId, buffer, index, length);
            break;
        case DataFW.TYPE_ID:
            handled = streamsBuffer.test(msgTypeId, buffer, index, length);
            break;
        case EndFW.TYPE_ID:
            handled = streamsBuffer.test(msgTypeId, buffer, index, length);

            final FrameFW end = frameRO.wrap(buffer, index, index + length);
            throttles.remove(end.streamId());
            break;
        case AbortFW.TYPE_ID:
            handled = streamsBuffer.test(msgTypeId, buffer, index, length);

            final FrameFW abort = frameRO.wrap(buffer, index, index + length);
            throttles.remove(abort.streamId());
            break;
        case WindowFW.TYPE_ID:
            handled = streamsBuffer.test(msgTypeId, buffer, index, length);
            break;
        case ResetFW.TYPE_ID:
            handled = streamsBuffer.test(msgTypeId, buffer, index, length);

            final FrameFW reset = frameRO.wrap(buffer, index, index + length);
            streams.remove(reset.streamId());
            break;
        default:
            handled = true;
            break;
        }

        if (!handled)
        {
            throw new IllegalStateException("Unable to write to streams buffer");
        }
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
