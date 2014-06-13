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

package org.eclipse.jetty.http2.client;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.hpack.MetaData;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

public class FlowControlTest extends AbstractTest
{
    @Test
    public void testFlowControlWithConcurrentSettings() throws Exception
    {
        // Initial window is 64 KiB. We allow the client to send 1024 B
        // then we change the window to 512 B. At this point, the client
        // must stop sending data (although the initial window allows it).

        final int size = 512;
        // We get 3 data frames: the first of 1024 and 2 of 512 each
        // after the flow control window has been reduced.
        final CountDownLatch dataLatch = new CountDownLatch(3);
        final AtomicReference<Callback> callbackRef = new AtomicReference<>();
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                HttpFields fields = new HttpFields();
                MetaData.Response response = new MetaData.Response(200, fields);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, true);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);

                return new Stream.Listener.Adapter()
                {
                    private final AtomicInteger dataFrames = new AtomicInteger();

                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        dataLatch.countDown();
                        int dataFrameCount = dataFrames.incrementAndGet();
                        if (dataFrameCount == 1)
                        {
                            callbackRef.set(callback);
                            Map<Integer, Integer> settings = new HashMap<>();
                            settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, size);
                            stream.getSession().settings(new SettingsFrame(settings, false), Callback.Adapter.INSTANCE);
                            // Do not succeed the callback here.
                        }
                        else if (dataFrameCount > 1)
                        {
                            // Consume the data.
                            callback.succeeded();
                        }
                    }
                };
            }
        });

        // Two SETTINGS frames, the initial one and the one we send.
        final CountDownLatch settingsLatch = new CountDownLatch(2);
        Session client = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        MetaData.Request request = newRequest("POST", new HttpFields());
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(new HeadersFrame(0, request, null, false), promise, new Stream.Listener.Adapter());
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        // Send first chunk that exceeds the window.
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(size * 2), false), Callback.Adapter.INSTANCE);
        settingsLatch.await(5, TimeUnit.SECONDS);

        // Send the second chunk of data, must not arrive since we're flow control stalled on the client.
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(size * 2), true), Callback.Adapter.INSTANCE);
        Assert.assertFalse(dataLatch.await(1, TimeUnit.SECONDS));

        // Consume the data arrived to server, this will resume flow control on the client.
        callbackRef.get().succeeded();

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerFlowControlOneBigWrite() throws Exception
    {
        final int windowSize = 1536;
        final int length = 5 * windowSize;
        final CountDownLatch settingsLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);

                DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.allocate(length), true);
                stream.data(dataFrame, Callback.Adapter.INSTANCE);
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        Map<Integer, Integer> settings = new HashMap<>();
        settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, windowSize);
        session.settings(new SettingsFrame(settings, false), Callback.Adapter.INSTANCE);

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch dataLatch = new CountDownLatch(1);
        final Exchanger<Callback> exchanger = new Exchanger<>();
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            private AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                try
                {
                    int dataFrames = this.dataFrames.incrementAndGet();
                    if (dataFrames == 1 || dataFrames == 2)
                    {
                        // Do not consume the data frame.
                        // We should then be flow-control stalled.
                        exchanger.exchange(callback);
                    }
                    else if (dataFrames == 3 || dataFrames == 4 || dataFrames == 5)
                    {
                        // Consume totally.
                        callback.succeeded();
                        if (frame.isEndStream())
                            dataLatch.countDown();
                    }
                    else
                    {
                        Assert.fail();
                    }
                }
                catch (InterruptedException x)
                {
                    callback.failed(x);
                }
            }
        });

        Callback callback = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the first chunk.
        callback.succeeded();

        callback = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the second chunk.
        callback.succeeded();

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    private void checkThatWeAreFlowControlStalled(Exchanger<Callback> exchanger) throws Exception
    {
        try
        {
            exchanger.exchange(null, 1, TimeUnit.SECONDS);
        }
        catch (TimeoutException x)
        {
            // Expected.
        }
    }

    @Test
    public void testClientFlowControlOneBigWrite() throws Exception
    {
        final int windowSize = 1536;
        final Exchanger<Callback> exchanger = new Exchanger<>();
        final CountDownLatch settingsLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, windowSize);
                return settings;
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response metaData = new MetaData.Response(200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, false);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                return new Stream.Listener.Adapter()
                {
                    private AtomicInteger dataFrames = new AtomicInteger();

                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        try
                        {
                            int dataFrames = this.dataFrames.incrementAndGet();
                            if (dataFrames == 1 || dataFrames == 2)
                            {
                                // Do not consume the data frame.
                                // We should then be flow-control stalled.
                                exchanger.exchange(callback);
                            }
                            else if (dataFrames == 3 || dataFrames == 4 || dataFrames == 5)
                            {
                                // Consume totally.
                                callback.succeeded();
                                if (frame.isEndStream())
                                    dataLatch.countDown();
                            }
                            else
                            {
                                Assert.fail();
                            }
                        }
                        catch (InterruptedException x)
                        {
                            callback.failed(x);
                        }
                    }
                };
            }
        });

        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }
        });

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        session.newStream(requestFrame, streamPromise, null);
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);

        final int length = 5 * windowSize;
        DataFrame dataFrame = new DataFrame(stream.getId(), ByteBuffer.allocate(length), true);
        stream.data(dataFrame, Callback.Adapter.INSTANCE);

        Callback callback = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the first chunk.
        callback.succeeded();

        callback = exchanger.exchange(null, 5, TimeUnit.SECONDS);
        checkThatWeAreFlowControlStalled(exchanger);

        // Consume the second chunk.
        callback.succeeded();

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    // TODO: add tests for session and stream flow control.

/*
    @Test
    public void testStreamsStalledDoesNotStallOtherStreams() throws Exception
    {
        final int windowSize = 1024;
        final CountDownLatch settingsLatch = new CountDownLatch(1);
        Session session = startClient(SPDY.V3, startServer(SPDY.V3, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsInfo settingsInfo)
            {
                settingsLatch.countDown();
            }

            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                stream.data(new BytesDataInfo(new byte[windowSize * 2], true), new Callback.Adapter());
                return null;
            }
        }), null);
        Settings settings = new Settings();
        settings.put(new Settings.Setting(Settings.ID.INITIAL_WINDOW_SIZE, windowSize));
        session.settings(new SettingsInfo(settings));

        Assert.assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        final CountDownLatch latch = new CountDownLatch(3);
        final AtomicReference<DataInfo> dataInfoRef1 = new AtomicReference<>();
        final AtomicReference<DataInfo> dataInfoRef2 = new AtomicReference<>();
        session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                int frames = dataFrames.incrementAndGet();
                if (frames == 1)
                {
                    // Do not consume it to stall flow control
                    dataInfoRef1.set(dataInfo);
                }
                else
                {
                    dataInfo.consume(dataInfo.length());
                    if (dataInfo.isClose())
                        latch.countDown();
                }
            }
        });
        session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), new StreamFrameListener.Adapter()
        {
            private final AtomicInteger dataFrames = new AtomicInteger();

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                int frames = dataFrames.incrementAndGet();
                if (frames == 1)
                {
                    // Do not consume it to stall flow control
                    dataInfoRef2.set(dataInfo);
                }
                else
                {
                    dataInfo.consume(dataInfo.length());
                    if (dataInfo.isClose())
                        latch.countDown();
                }
            }
        });
        session.syn(new SynInfo(5, TimeUnit.SECONDS, new Fields(), true, (byte)0), new StreamFrameListener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                DataInfo dataInfo1 = dataInfoRef1.getAndSet(null);
                if (dataInfo1 != null)
                    dataInfo1.consume(dataInfo1.length());
                DataInfo dataInfo2 = dataInfoRef2.getAndSet(null);
                if (dataInfo2 != null)
                    dataInfo2.consume(dataInfo2.length());
                dataInfo.consume(dataInfo.length());
                if (dataInfo.isClose())
                    latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSendBigFileWithoutFlowControl() throws Exception
    {
        testSendBigFile(SPDY.V2);
    }

    @Test
    public void testSendBigFileWithFlowControl() throws Exception
    {
        testSendBigFile(SPDY.V3);
    }

    private void testSendBigFile(short version) throws Exception
    {
        final int dataSize = 1024 * 1024;
        final ByteBufferDataInfo bigByteBufferDataInfo = new ByteBufferDataInfo(ByteBuffer.allocate(dataSize),false);
        final CountDownLatch allDataReceivedLatch = new CountDownLatch(1);

        Session session = startClient(version, startServer(version, new ServerSessionFrameListener.Adapter()
        {
            @Override
            public StreamFrameListener onSyn(Stream stream, SynInfo synInfo)
            {
                stream.reply(new ReplyInfo(false), new Callback.Adapter());
                stream.data(bigByteBufferDataInfo, new Callback.Adapter());
                return null;
            }
        }),new SessionFrameListener.Adapter());

        session.syn(new SynInfo(new Fields(), false),new StreamFrameListener.Adapter()
        {
            private int dataBytesReceived;

            @Override
            public void onData(Stream stream, DataInfo dataInfo)
            {
                dataBytesReceived = dataBytesReceived + dataInfo.length();
                dataInfo.consume(dataInfo.length());
                if (dataBytesReceived == dataSize)
                    allDataReceivedLatch.countDown();
            }
        });

        assertThat("all data bytes have been received by the client", allDataReceivedLatch.await(5, TimeUnit.SECONDS), is(true));
    }
*/
}
