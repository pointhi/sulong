/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.nodes.intrinsics.interop;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.llvm.nodes.intrinsics.interop.LLVMPolyglotEvalNodeGen.GetSourceFileNodeGen;
import com.oracle.truffle.llvm.nodes.intrinsics.interop.LLVMPolyglotEvalNodeGen.GetSourceStringNodeGen;
import com.oracle.truffle.llvm.nodes.intrinsics.llvm.LLVMIntrinsic;
import com.oracle.truffle.llvm.runtime.LLVMContext;
import com.oracle.truffle.llvm.runtime.except.LLVMPolyglotException;
import com.oracle.truffle.llvm.runtime.interop.convert.ForeignToLLVM;
import com.oracle.truffle.llvm.runtime.interop.convert.ForeignToLLVM.ForeignToLLVMType;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMExpressionNode;
import com.oracle.truffle.llvm.runtime.nodes.api.LLVMNode;
import java.io.IOException;

@NodeChild(type = LLVMExpressionNode.class)
@NodeChild(type = LLVMExpressionNode.class)
public abstract class LLVMPolyglotEval extends LLVMIntrinsic {

    @Child GetSourceNode getSource;
    @Child ForeignToLLVM toLLVM = ForeignToLLVM.create(ForeignToLLVMType.POINTER);

    public static LLVMPolyglotEval create(LLVMExpressionNode id, LLVMExpressionNode code) {
        return LLVMPolyglotEvalNodeGen.create(GetSourceStringNodeGen.create(false), id, code);
    }

    public static LLVMPolyglotEval createFile(LLVMExpressionNode id, LLVMExpressionNode filename) {
        return LLVMPolyglotEvalNodeGen.create(GetSourceFileNodeGen.create(), id, filename);
    }

    public static LLVMPolyglotEval createLegacy(LLVMExpressionNode mime, LLVMExpressionNode code) {
        return LLVMPolyglotEvalNodeGen.create(GetSourceStringNodeGen.create(true), mime, code);
    }

    LLVMPolyglotEval(GetSourceNode getSource) {
        this.getSource = getSource;
    }

    @Specialization
    protected Object doEval(Object idPointer, Object srcPointer,
                    @Cached("createReadString()") LLVMReadStringNode readId,
                    @Cached("createReadString()") LLVMReadStringNode readSrc) {
        try {
            CallTarget callTarget = getSource.execute(readId.executeWithTarget(idPointer), readSrc.executeWithTarget(srcPointer));
            Object foreign = callTarget.call();
            return toLLVM.executeWithTarget(foreign);
        } catch (IllegalStateException e) {
            // language id not found
            CompilerDirectives.transferToInterpreter();
            throw new LLVMPolyglotException(this, e.getMessage());
        }
    }

    abstract static class GetSourceNode extends LLVMNode {

        abstract CallTarget execute(String languageId, String source);
    }

    abstract static class GetSourceStringNode extends GetSourceNode {

        private final boolean legacyMimeTypeEval;

        protected GetSourceStringNode(boolean legacyMimeTypeEval) {
            this.legacyMimeTypeEval = legacyMimeTypeEval;
        }

        @SuppressWarnings("unused")
        @Specialization(limit = "2", guards = {"id.equals(cachedId)", "code.equals(cachedCode)"})
        CallTarget doCached(String id, String code,
                        @Cached("id") String cachedId,
                        @Cached("code") String cachedCode,
                        @Cached("getContextReference()") ContextReference<LLVMContext> ctxRef,
                        @Cached("uncached(cachedId, cachedCode, ctxRef)") CallTarget callTarget) {
            return callTarget;
        }

        @TruffleBoundary
        @Specialization(replaces = "doCached")
        CallTarget uncached(String id, String code,
                        @Cached("getContextReference()") ContextReference<LLVMContext> ctxRef) {
            Source sourceObject;
            if (legacyMimeTypeEval) {
                sourceObject = Source.newBuilder(code).name("<eval>").mimeType(id).build();
            } else {
                sourceObject = Source.newBuilder(code).name("<eval>").language(id).build();
            }
            return ctxRef.get().getEnv().parse(sourceObject);
        }
    }

    abstract static class GetSourceFileNode extends GetSourceNode {

        @TruffleBoundary
        @Specialization
        CallTarget uncached(String id, String filename,
                        @Cached("getContextReference()") ContextReference<LLVMContext> ctxRef) {
            try {
                // never cache, since the file content could change between invocations
                Env env = ctxRef.get().getEnv();
                Source sourceObject = env.newSourceBuilder(env.getTruffleFile(filename)).name("<eval>").language(id).build();
                return env.parse(sourceObject);
            } catch (IOException ex) {
                throw new LLVMPolyglotException(this, "Could not parse file %s (%s).", filename, ex.getMessage());
            }
        }
    }
}
