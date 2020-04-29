//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

public class CongestedServerTest
{
    private Server server;
    private WebSocketClient client;

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setMaxIdleTimeout(60000);
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder websocket = new ServletHolder(new WebSocketServlet()
        {
            @Override
            public void configure(WebSocketServletFactory factory)
            {
                factory.register(SlowServerReaderEndpoint.class);
            }
        });
        context.addServlet(websocket, "/ws");

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        server.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(server);
        LifeCycle.stop(client);
    }

    @Test
    public void testBackpressure() throws Exception
    {
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ws"));
        ClientEndpoint clientEndpoint = new ClientEndpoint();
        Future<Session> sessionFut = client.connect(clientEndpoint, wsUri);
        Session session = sessionFut.get(5, TimeUnit.SECONDS);

        int messages = 300;
        RemoteEndpoint remote = session.getRemote();
        CountDownLatch sendLatch = new CountDownLatch(messages);
        List<TimingCallback> callbacks = new ArrayList<>();
        char[] buf = new char[1024 * 64];
        Arrays.fill(buf, 'x');

        for (int i = 0; i < messages; i++)
        {
            TimingCallback callback = new TimingCallback(i, sendLatch);
            remote.sendString(new String(buf), callback);
            callbacks.add(callback);
        }

        sendLatch.await();
        callbacks.forEach(System.out::println);

        assertFalse(callbacks.stream().anyMatch(TimingCallback::isFailed), "No failures should have occurred");
    }

    public static class TimingCallback implements WriteCallback
    {
        private transient CountDownLatch sendLatch;
        private int msgNum;
        private long start;
        private long end;
        private Throwable failed;

        public TimingCallback(int msgNum, CountDownLatch sendLatch)
        {
            this.msgNum = msgNum;
            this.sendLatch = sendLatch;
            this.start = System.currentTimeMillis();
        }

        public boolean isFailed()
        {
            return this.failed != null;
        }

        @Override
        public void writeFailed(Throwable cause)
        {
            this.sendLatch.countDown();
            this.failed = cause;
            this.end = System.currentTimeMillis();
        }

        @Override
        public void writeSuccess()
        {
            this.sendLatch.countDown();
            this.end = System.currentTimeMillis();
        }

        @Override
        public String toString()
        {
            final StringBuilder sb = new StringBuilder("TimingCallback[");
            sb.append(msgNum);
            sb.append(",dur=").append(end - start);
            if (isFailed())
            {
                sb.append(",failed=").append(failed.getClass().getName());
                sb.append(' ').append(failed.getMessage());
            }
            else
            {
                sb.append(",success");
            }
            sb.append(']');
            return sb.toString();
        }
    }

    @WebSocket
    public static class ClientEndpoint
    {
    }

    @WebSocket
    public static class SlowServerReaderEndpoint
    {
        private long msgCount = 0;
        private long timeout = 5000;

        @OnWebSocketMessage
        public void onMessage(Session session, String msg)
        {
            if (((msgCount % 50) == 0) && (timeout > 0))
            {
                try
                {
                    Thread.sleep(timeout);
                }
                catch (InterruptedException ignored)
                {
                }

                // reduce timeout
                timeout -= 200;
            }
            msgCount++;
        }
    }
}
