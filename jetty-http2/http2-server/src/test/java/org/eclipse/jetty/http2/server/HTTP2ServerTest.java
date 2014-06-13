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

package org.eclipse.jetty.http2.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.hpack.HpackContext;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.MetaData;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.parser.PrefaceParser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class HTTP2ServerTest
{
    private Server server;
    private ServerConnector connector;
    private String path;
    private ByteBufferPool byteBufferPool;
    private Generator generator;

    private void startServer(HttpServlet servlet) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, new HTTP2ServerConnectionFactory(new HttpConfiguration()));
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/");
        path = "/test";
        context.addServlet(new ServletHolder(servlet), path);

        byteBufferPool = new MappedByteBufferPool();
        generator = new Generator(byteBufferPool);

        server.start();
        HpackContext.LOG.setDebugEnabled(true);
        HpackEncoder.LOG.setDebugEnabled(true);
        HpackDecoder.LOG.setDebugEnabled(true);
    }

    @After
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testNoPrefaceBytes() throws Exception
    {
        startServer(new HttpServlet(){});

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = new MetaData.Request(HttpScheme.HTTP, HttpMethod.GET.asString(),
                host + ":" + port, host, port, path, fields);
        HeadersFrame request = new HeadersFrame(1, metaData, null, true);
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.generate(lease, request, 0);

        // No preface bytes

        try (Socket client = new Socket(host, port))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            final CountDownLatch latch = new CountDownLatch(1);
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public boolean onGoAway(GoAwayFrame frame)
                {
                    latch.countDown();
                    return false;
                }
            });

            parseResponse(client, parser);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testRequestResponseNoContent() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(3);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                latch.countDown();
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = new MetaData.Request(HttpScheme.HTTP, HttpMethod.GET.asString(),
                host + ":" + port, host, port, path, fields);
        HeadersFrame request = new HeadersFrame(1, metaData, null, true);
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.generate(lease, request, 0);
        lease.prepend(ByteBuffer.wrap(PrefaceParser.PREFACE_BYTES), false);

        try (Socket client = new Socket(host, port))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            final AtomicReference<HeadersFrame> frameRef = new AtomicReference<>();
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public boolean onSettings(SettingsFrame frame)
                {
                    latch.countDown();
                    return false;
                }

                @Override
                public boolean onHeaders(HeadersFrame frame)
                {
                    frameRef.set(frame);
                    latch.countDown();
                    return false;
                }
            });

            parseResponse(client, parser);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

            HeadersFrame response = frameRef.get();
            Assert.assertNotNull(response);
            MetaData.Response responseMetaData = (MetaData.Response)response.getMetaData();
            Assert.assertEquals(200, responseMetaData.getStatus());
        }
    }

    @Test
    public void testRequestResponseContent() throws Exception
    {
        final byte[] content = "Hello, world!".getBytes(StandardCharsets.UTF_8);
        final CountDownLatch latch = new CountDownLatch(4);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                latch.countDown();
                resp.getOutputStream().write(content);
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = new MetaData.Request(HttpScheme.HTTP, HttpMethod.GET.asString(),
                host + ":" + port, host, port, path, fields);
        HeadersFrame request = new HeadersFrame(1, metaData, null, true);
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.generate(lease, request, 0);
        lease.prepend(ByteBuffer.wrap(PrefaceParser.PREFACE_BYTES), false);

        try (Socket client = new Socket(host, port))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            final AtomicReference<HeadersFrame> headersRef = new AtomicReference<>();
            final AtomicReference<DataFrame> dataRef = new AtomicReference<>();
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public boolean onSettings(SettingsFrame frame)
                {
                    latch.countDown();
                    return false;
                }

                @Override
                public boolean onHeaders(HeadersFrame frame)
                {
                    headersRef.set(frame);
                    latch.countDown();
                    return false;
                }

                @Override
                public boolean onData(DataFrame frame)
                {
                    dataRef.set(frame);
                    latch.countDown();
                    return false;
                }
            });

            parseResponse(client, parser);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

            HeadersFrame response = headersRef.get();
            Assert.assertNotNull(response);
            MetaData.Response responseMetaData = (MetaData.Response)response.getMetaData();
            Assert.assertEquals(200, responseMetaData.getStatus());

            DataFrame responseData = dataRef.get();
            Assert.assertNotNull(responseData);
            Assert.assertArrayEquals(content, BufferUtil.toArray(responseData.getData()));
        }
    }

    private void parseResponse(Socket client, Parser parser) throws IOException
    {
        byte[] buffer = new byte[2048];
        InputStream input = client.getInputStream();
        client.setSoTimeout(1000);
        while (true)
        {
            try
            {
                int read = input.read(buffer);
                if (read <= 0)
                    throw new EOFException();
                parser.parse(ByteBuffer.wrap(buffer, 0, read));
            }
            catch (SocketTimeoutException x)
            {
                break;
            }
        }
    }
}
