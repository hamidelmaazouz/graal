/*
 * Copyright (c) 2019, 2020, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.nativemode;

import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.runtime.ToolchainConfig;
import com.oracle.truffle.nfi.api.SignatureLibrary;

final class NativeToolchainConfig implements ToolchainConfig {

    private static final NativeToolchainConfig INSTANCE = new NativeToolchainConfig();

    /**
     * @deprecated "This method should not be called directly. Use
     *             {@link LLVMLanguage#getCapability(Class)} instead."
     */
    @Deprecated
    static NativeToolchainConfig getInstance() {
        return INSTANCE;
    }

    private static final String TOOLCHAIN_ROOT_NAME = "llvm.toolchainRoot";
    private static final String TOOLCHAIN_ROOT = System.getProperty(TOOLCHAIN_ROOT_NAME);

    @Override
    public String getToolchainRootOverride() {
        return TOOLCHAIN_ROOT;
    }

    @Override
    public String getToolchainSubdir() {
        return "native";
    }

    @Override
    public boolean enableCXX() {
        return true;
    }

    @Override
    public Object bind(Object signature, Object function) {
        return SignatureLibrary.getUncached().bind(signature, function);
    }
}
