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

package org.eclipse.jetty.servlet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HotSwapHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class ServletContextListenerTest
{
    private Server server;

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(server);
    }

    private Server newServer()
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        return server;
    }

    @Test
    public void testServletContextListener() throws Exception
    {
        Server server = newServer();
        HotSwapHandler swap = new HotSwapHandler();
        server.setHandler(swap);
        server.start();

        Path tempDir = MavenTestingUtils.getTargetTestingPath("testServletContextListener");
        FS.ensureEmpty(tempDir);

        ServletContextHandler context = new ServletContextHandler(
            ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.setBaseResource(new PathResource(tempDir));

        final List<String> history = new ArrayList<>();

        context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent servletContextEvent)
            {
                history.add("I0");
            }

            @Override
            public void contextDestroyed(ServletContextEvent servletContextEvent)
            {
                history.add("D0");
            }
        });
        context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent servletContextEvent)
            {
                history.add("I1");
            }

            @Override
            public void contextDestroyed(ServletContextEvent servletContextEvent)
            {
                history.add("D1");
                throw new RuntimeException("Listener1 destroy broken");
            }
        });
        context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent servletContextEvent)
            {
                history.add("I2");
                throw new RuntimeException("Listener2 init broken");
            }

            @Override
            public void contextDestroyed(ServletContextEvent servletContextEvent)
            {
                history.add("D2");
            }
        });
        context.addEventListener(new ServletContextListener()
        {
            @Override
            public void contextInitialized(ServletContextEvent servletContextEvent)
            {
                history.add("I3");
            }

            @Override
            public void contextDestroyed(ServletContextEvent servletContextEvent)
            {
                history.add("D3");
            }
        });

        try
        {
            swap.setHandler(context);
            context.start();
        }
        catch (Exception e)
        {
            history.add(e.getMessage());
        }
        finally
        {
            try
            {
                swap.setHandler(null);
            }
            catch (Exception e)
            {
                while (e.getCause() instanceof Exception)
                {
                    e = (Exception)e.getCause();
                }
                history.add(e.getMessage());
            }
        }

        assertThat(history, contains("I0", "I1", "I2", "Listener2 init broken", "D1", "D0", "Listener1 destroy broken"));
    }
}
