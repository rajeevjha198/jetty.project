//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Flag;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class DataGenerator extends FrameGenerator
{
    public DataGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public void generate(ByteBufferPool.Lease lease, Frame frame, int maxLength)
    {
        DataFrame dataFrame = (DataFrame)frame;
        generateData(lease, dataFrame.getStreamId(), dataFrame.getData(), dataFrame.isEndStream(), maxLength);
    }

    public void generateData(ByteBufferPool.Lease lease, int streamId, ByteBuffer data, boolean last, int maxLength)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        int dataLength = data.remaining();
        if (dataLength <= maxLength && dataLength <= Frame.MAX_LENGTH)
        {
            // Single frame.
            generateFrame(lease, streamId, data, last);
            return;
        }

        // Other cases, we need to slice the original buffer into multiple frames.

        int length = Math.min(maxLength, dataLength);
        int dataBytesPerFrame = Frame.MAX_LENGTH;
        int frames = length / dataBytesPerFrame;
        if (frames * dataBytesPerFrame != length)
            ++frames;

        int begin = data.position();
        int end = data.limit();
        for (int i = 1; i <= frames; ++i)
        {
            data.limit(begin + Math.min(dataBytesPerFrame * i, length));
            ByteBuffer slice = data.slice();
            data.position(data.limit());
            generateFrame(lease, streamId, slice, i == frames && last);
        }
        data.limit(end);
    }

    private void generateFrame(ByteBufferPool.Lease lease, int streamId, ByteBuffer data, boolean last)
    {
        int length = data.remaining();

        int flags = Flag.NONE;
        if (last)
            flags |= Flag.END_STREAM;

        ByteBuffer header = generateHeader(lease, FrameType.DATA, length, flags, streamId);

        BufferUtil.flipToFlush(header, 0);
        lease.append(header, true);

        lease.append(data, false);
    }
}
