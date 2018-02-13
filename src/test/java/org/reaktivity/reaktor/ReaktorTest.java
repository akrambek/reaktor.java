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
package org.reaktivity.reaktor;

import static org.junit.Assert.assertNotSame;

import java.util.function.BooleanSupplier;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.IdleStrategy;
import org.jmock.Expectations;
import org.jmock.integration.junit4.JUnitRuleMockery;
import org.jmock.lib.concurrent.Synchroniser;
import org.junit.Rule;
import org.junit.Test;
import org.reaktivity.nukleus.Controller;
import org.reaktivity.nukleus.Nukleus;

public class ReaktorTest
{

    @Rule
    public JUnitRuleMockery context = new JUnitRuleMockery()
    {
        {
            setThreadingPolicy(new Synchroniser());
        }
    };

    @Test
    public void shouldCloseControllers() throws Exception
    {
        final Controller controller = context.mock(Controller.class);
        final IdleStrategy idleStrategy = context.mock(IdleStrategy.class);
        final ErrorHandler errorHandler = context.mock(ErrorHandler.class);
        final BooleanSupplier memoryReleased = context.mock(BooleanSupplier.class);
        final AutoCloseable memoryLayout = context.mock(AutoCloseable.class);

        context.checking(new Expectations()
        {
            {
                allowing(controller).process(); will(returnValue(0));
                allowing(idleStrategy).idle(with(any(int.class)));

                oneOf(memoryReleased).getAsBoolean(); will(returnValue(true));
                oneOf(controller).kind(); will(returnValue(Controller.class));
                oneOf(controller).close();
                oneOf(memoryLayout).close();
            }
        });
        Reaktor reaktor = new Reaktor(idleStrategy, errorHandler, new Nukleus[0], new Controller[]{controller},
                                      memoryLayout, memoryReleased);
        reaktor.start();
        reaktor.close();
    }

    @Test
    public void shouldCloseNuklei() throws Exception
    {
        final Nukleus nukleus = context.mock(Nukleus.class);
        final IdleStrategy idleStrategy = context.mock(IdleStrategy.class);
        final ErrorHandler errorHandler = context.mock(ErrorHandler.class);
        final BooleanSupplier memoryReleased = context.mock(BooleanSupplier.class);
        final AutoCloseable memoryLayout = context.mock(AutoCloseable.class);

        context.checking(new Expectations()
        {
            {
                allowing(nukleus).process(); will(returnValue(0));
                allowing(idleStrategy).idle(with(any(int.class)));

                oneOf(memoryReleased).getAsBoolean(); will(returnValue(true));
                oneOf(nukleus).name(); will(returnValue("nukleus-name"));
                oneOf(nukleus).close();
                oneOf(memoryLayout).close();
            }
        });
        Reaktor reaktor = new Reaktor(idleStrategy, errorHandler, new Nukleus[]{nukleus}, new Controller[0],
                                      memoryLayout, memoryReleased);
        reaktor.start();
        reaktor.close();
    }

    @Test(expected = Exception.class)
    public void shouldReportControllerCloseError() throws Exception
    {
        final Controller controller = context.mock(Controller.class);
        final IdleStrategy idleStrategy = context.mock(IdleStrategy.class);
        final ErrorHandler errorHandler = context.mock(ErrorHandler.class);
        final BooleanSupplier memoryReleased = context.mock(BooleanSupplier.class);
        final AutoCloseable memoryLayout = context.mock(AutoCloseable.class);

        context.checking(new Expectations()
        {
            {
                allowing(controller).process(); will(returnValue(0));
                allowing(idleStrategy).idle(with(any(int.class)));

                oneOf(memoryReleased).getAsBoolean(); will(returnValue(true));
                oneOf(controller).kind(); will(returnValue(Controller.class));
                oneOf(controller).close(); will(throwException(new Exception("controller close failed")));
                oneOf(memoryLayout).close();
            }
        });
        Reaktor reaktor = new Reaktor(idleStrategy, errorHandler, new Nukleus[0], new Controller[]{controller},
                                      memoryLayout, memoryReleased);
        reaktor.start();
        try
        {
            reaktor.close();
        }
        catch(Throwable t)
        {
            assert(t.getSuppressed().length == 0);
            throw t;
        }
    }

    @Test(expected = Exception.class)
    public void shouldReportNukleusCloseError() throws Exception
    {
        final Nukleus nukleus = context.mock(Nukleus.class);
        final IdleStrategy idleStrategy = context.mock(IdleStrategy.class);
        final ErrorHandler errorHandler = context.mock(ErrorHandler.class);
        final BooleanSupplier memoryReleased = context.mock(BooleanSupplier.class);
        final AutoCloseable memoryLayout = context.mock(AutoCloseable.class);

        context.checking(new Expectations()
        {
            {
                allowing(nukleus).process(); will(returnValue(0));
                allowing(idleStrategy).idle(with(any(int.class)));

                oneOf(memoryReleased).getAsBoolean(); will(returnValue(true));
                oneOf(nukleus).name(); will(returnValue("nukleus-name"));
                oneOf(nukleus).close(); will(throwException(new Exception("Nukleus close failed")));
                oneOf(memoryLayout).close();
            }
        });
        Reaktor reaktor = new Reaktor(idleStrategy, errorHandler, new Nukleus[]{nukleus}, new Controller[0],
                                      memoryLayout, memoryReleased);
        reaktor.start();
        try
        {
            reaktor.close();
        }
        catch(Throwable t)
        {
            assert(t.getSuppressed().length == 0);
            throw t;
        }
    }

    @Test(expected = Exception.class)
    public void shouldReportAllCloseErrors() throws Exception
    {
        final Controller controller = context.mock(Controller.class);
        final Nukleus nukleus = context.mock(Nukleus.class);
        final IdleStrategy idleStrategy = context.mock(IdleStrategy.class);
        final ErrorHandler errorHandler = context.mock(ErrorHandler.class);
        final BooleanSupplier memoryReleased = context.mock(BooleanSupplier.class);
        final AutoCloseable memoryLayout = context.mock(AutoCloseable.class);

        context.checking(new Expectations()
        {
            {
                allowing(controller).process(); will(returnValue(0));
                allowing(nukleus).process(); will(returnValue(0));
                allowing(idleStrategy).idle(with(any(int.class)));

                oneOf(memoryReleased).getAsBoolean(); will(returnValue(true));
                oneOf(controller).kind(); will(returnValue(Controller.class));
                oneOf(nukleus).name(); will(returnValue("nukleus-name"));
                oneOf(controller).close(); will(throwException(new Exception("controller close failed")));
                oneOf(nukleus).close(); will(throwException(new Exception("Nukleus close failed")));
                oneOf(memoryLayout).close();
            }
        });
        Reaktor reaktor = new Reaktor(idleStrategy, errorHandler, new Nukleus[]{nukleus}, new Controller[]{controller},
                                      memoryLayout, memoryReleased);
        reaktor.start();
        try
        {
            reaktor.close();
        }
        catch(Throwable t)
        {
            assert(t.getSuppressed().length == 1);
            assertNotSame(t, t.getSuppressed()[0]);
            throw t;
        }
    }
}
