/*
 * Copyright (c) 2017, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.nodes.memory.literal;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.llvm.nodes.memory.store.LLVM80BitFloatStoreNode;
import com.oracle.truffle.llvm.nodes.memory.store.LLVM80BitFloatStoreNodeGen;
import com.oracle.truffle.llvm.runtime.floating.LLVM80BitFloat;
import com.oracle.truffle.llvm.runtime.global.LLVMGlobal;
import com.oracle.truffle.llvm.runtime.memory.LLVMMemory;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMToNativeNode;
import com.oracle.truffle.llvm.runtime.pointer.LLVMManagedPointer;
import com.oracle.truffle.llvm.runtime.pointer.LLVMNativePointer;

@NodeChild(value = "address", type = LLVMExpressionNode.class)
public abstract class LLVM80BitFloatArrayLiteralNode extends LLVMExpressionNode {

    @Children private final LLVMExpressionNode[] values;
    private final int stride;

    public LLVM80BitFloatArrayLiteralNode(LLVMExpressionNode[] values, int stride) {
        this.values = values;
        this.stride = stride;
    }

    @Specialization
    protected LLVMNativePointer write(VirtualFrame frame, LLVMGlobal global,
                    @Cached("createToNativeWithTarget()") LLVMToNativeNode toNative,
                    @Cached("getLLVMMemory()") LLVMMemory memory) {
        return write80BitFloat(frame, toNative.executeWithTarget(global), memory);
    }

    @Specialization
    @ExplodeLoop
    protected LLVMNativePointer write80BitFloat(VirtualFrame frame, LLVMNativePointer addr,
                    @Cached("getLLVMMemory()") LLVMMemory memory) {
        long currentPtr = addr.asNative();
        for (int i = 0; i < values.length; i++) {
            try {
                LLVM80BitFloat currentValue = values[i].executeLLVM80BitFloat(frame);
                memory.put80BitFloat(currentPtr, currentValue);
                currentPtr += stride;
            } catch (UnexpectedResultException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        }
        return addr;
    }

    @Specialization
    @ExplodeLoop
    protected LLVMManagedPointer foreignWrite(VirtualFrame frame, LLVMManagedPointer addr,
                    @Cached("create80BitFloatStore()") LLVM80BitFloatStoreNode write) {
        LLVMManagedPointer currentPtr = addr;
        for (int i = 0; i < values.length; i++) {
            try {
                LLVM80BitFloat currentValue = values[i].executeLLVM80BitFloat(frame);
                write.executeWithTarget(currentPtr, currentValue);
                currentPtr = currentPtr.increment(stride);
            } catch (UnexpectedResultException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        }
        return addr;
    }

    protected LLVM80BitFloatStoreNode create80BitFloatStore() {
        return LLVM80BitFloatStoreNodeGen.create(null, null);
    }
}
