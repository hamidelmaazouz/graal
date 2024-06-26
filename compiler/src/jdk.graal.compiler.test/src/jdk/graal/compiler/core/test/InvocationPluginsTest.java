/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.core.test;

import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugin.Receiver;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins.LateRegistration;
import jdk.graal.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import org.junit.Test;

import jdk.vm.ci.meta.ResolvedJavaMethod;

public class InvocationPluginsTest extends GraalCompilerTest {

    private static void assertNotIsEmpty(InvocationPlugins invocationPlugins) {
        InvocationPlugins childInvocationPlugins = new InvocationPlugins(null, invocationPlugins);
        assertFalse(invocationPlugins.isEmpty());
        assertFalse(childInvocationPlugins.isEmpty());

        invocationPlugins.closeRegistration();
        assertFalse(invocationPlugins.isEmpty());
        assertFalse(childInvocationPlugins.isEmpty());
    }

    @Test
    public void testIsEmptyWithNormalRegistration() {
        InvocationPlugins invocationPlugins = new InvocationPlugins();
        assertTrue(invocationPlugins.isEmpty());
        assertTrue(invocationPlugins.toString().length() == 0);

        Registration r = new Registration(invocationPlugins, Class.class);
        r.register(new InvocationPlugin("isAnonymousClass", Receiver.class) {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                return false;
            }
        });

        assertNotIsEmpty(invocationPlugins);
    }

    @Test
    public void testIsEmptyWithDeferredRegistration() {
        InvocationPlugins invocationPlugins = new InvocationPlugins();
        assertTrue(invocationPlugins.isEmpty());
        assertTrue(invocationPlugins.toString().length() == 0);
        invocationPlugins.defer(new Runnable() {

            @Override
            public void run() {
                Registration r = new Registration(invocationPlugins, Class.class);
                r.register(new InvocationPlugin("isAnonymousClass", Receiver.class) {
                    @Override
                    public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                        return false;
                    }
                });
            }
        });

        assertNotIsEmpty(invocationPlugins);
    }

    @Test
    public void testIsEmptyWithLateRegistration() {
        InvocationPlugins invocationPlugins = new InvocationPlugins();
        assertTrue(invocationPlugins.isEmpty());
        assertTrue(invocationPlugins.toString().length() == 0);

        try (LateRegistration lr = new LateRegistration(invocationPlugins, Class.class)) {
            lr.register(new InvocationPlugin("isAnonymousClass", Receiver.class) {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver) {
                    return false;
                }
            });
        }
        assertNotIsEmpty(invocationPlugins);
    }
}
