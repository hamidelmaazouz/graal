/*
 * Copyright (c) 2019, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.truffle.espresso.impl;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.interop.ForeignAccess;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.espresso.Utils;
import com.oracle.truffle.espresso.classfile.CodeAttribute;
import com.oracle.truffle.espresso.classfile.ConstantPool;
import com.oracle.truffle.espresso.classfile.Constants;
import com.oracle.truffle.espresso.classfile.ExceptionsAttribute;
import com.oracle.truffle.espresso.classfile.RuntimeConstantPool;
import com.oracle.truffle.espresso.classfile.SourceFileAttribute;
import com.oracle.truffle.espresso.descriptors.Signatures;
import com.oracle.truffle.espresso.descriptors.Symbol;
import com.oracle.truffle.espresso.descriptors.Symbol.Name;
import com.oracle.truffle.espresso.descriptors.Symbol.Signature;
import com.oracle.truffle.espresso.descriptors.Symbol.Type;
import com.oracle.truffle.espresso.jni.Mangle;
import com.oracle.truffle.espresso.jni.NativeLibrary;
import com.oracle.truffle.espresso.meta.ExceptionHandler;
import com.oracle.truffle.espresso.meta.JavaKind;
import com.oracle.truffle.espresso.meta.Meta;
import com.oracle.truffle.espresso.meta.MetaUtil;
import com.oracle.truffle.espresso.meta.ModifiersProvider;
import com.oracle.truffle.espresso.nodes.BytecodeNode;
import com.oracle.truffle.espresso.nodes.EspressoBaseNode;
import com.oracle.truffle.espresso.nodes.EspressoRootNode;
import com.oracle.truffle.espresso.nodes.NativeRootNode;
import com.oracle.truffle.espresso.runtime.Attribute;
import com.oracle.truffle.espresso.runtime.BootstrapMethodsAttribute;
import com.oracle.truffle.espresso.runtime.EspressoContext;
import com.oracle.truffle.espresso.runtime.MethodHandleIntrinsics;
import com.oracle.truffle.espresso.runtime.StaticObject;
import com.oracle.truffle.nfi.types.NativeSimpleType;

import java.lang.reflect.Modifier;
import java.util.function.Function;

import static com.oracle.truffle.espresso.classfile.Constants.REF_invokeInterface;
import static com.oracle.truffle.espresso.classfile.Constants.REF_invokeSpecial;
import static com.oracle.truffle.espresso.classfile.Constants.REF_invokeStatic;
import static com.oracle.truffle.espresso.classfile.Constants.REF_invokeVirtual;

public final class Method implements TruffleObject, ModifiersProvider, ContextAccess {
    public static final Method[] EMPTY_ARRAY = new Method[0];

    private final LinkedMethod linkedMethod;
    private final RuntimeConstantPool pool;

    private final ObjectKlass declaringKlass;

    private final Symbol<Name> name;

    private final Symbol<Signature> rawSignature;

    @CompilationFinal(dimensions = 1) //
    private final Symbol<Type>[] parsedSignature;

    @CompilationFinal private int vtableIndex = -1;
    @CompilationFinal private int itableIndex = -1;

    private final ExceptionsAttribute exceptionsAttribute;
    private final CodeAttribute codeAttribute;

    @CompilationFinal private int refKind;

    @CompilationFinal //
    private CallTarget callTarget;

    @CompilationFinal(dimensions = 1) //
    private ObjectKlass[] checkedExceptions;

    private final Method proxy;

    // Multiple maximally-specific interface methods. Fail on call.
    @CompilationFinal private boolean poisonPill = false;

    // can have a different constant pool than it's declaring class
    public ConstantPool getConstantPool() {
        return pool;
    }

    public RuntimeConstantPool getRuntimeConstantPool() {
        return pool;
    }

    public Klass getDeclaringKlass() {
        return declaringKlass;
    }

    public Symbol<Name> getName() {
        return name;
    }

    public Symbol<Signature> getRawSignature() {
        return rawSignature;
    }

    public Symbol<Type>[] getParsedSignature() {
        return parsedSignature;
    }

    Method(Method method) {
        this.declaringKlass = method.declaringKlass;
        // TODO(peterssen): Custom constant pool for methods is not supported.
        this.pool = (RuntimeConstantPool) method.getConstantPool();

        this.name = method.linkedMethod.getName();
        this.linkedMethod = method.linkedMethod;

        this.rawSignature = method.getRawSignature();
        this.parsedSignature = getSignatures().parsed(this.rawSignature);

        this.codeAttribute = method.codeAttribute;
        this.callTarget = method.callTarget;

        this.exceptionsAttribute = (ExceptionsAttribute) getAttribute(ExceptionsAttribute.NAME);

        initRefKind();
        // Proxy the method, so that we have the same callTarget if it is not yet initialized.
        // Allows for not duplicating the codeAttribute
        this.proxy = method;
    }

    Method(ObjectKlass declaringKlass, LinkedMethod linkedMethod) {
        this(declaringKlass, linkedMethod, linkedMethod.getRawSignature());
    }

    Method(ObjectKlass declaringKlass, LinkedMethod linkedMethod, Symbol<Signature> rawSignature) {

        this.declaringKlass = declaringKlass;
        // TODO(peterssen): Custom constant pool for methods is not supported.
        this.pool = declaringKlass.getConstantPool();

        this.name = linkedMethod.getName();
        this.linkedMethod = linkedMethod;

        this.rawSignature = rawSignature;
        this.parsedSignature = getSignatures().parsed(this.rawSignature);

        this.codeAttribute = (CodeAttribute) getAttribute(CodeAttribute.NAME);
        this.exceptionsAttribute = (ExceptionsAttribute) getAttribute(ExceptionsAttribute.NAME);

        initRefKind();
        this.proxy = null;
    }

    public final int getRefKind() {
        return refKind;
    }

    public final void initRefKind() {
        if (isStatic()) {
            this.refKind = REF_invokeStatic;
        } else if (isPrivate() || isConstructor()) {
            this.refKind = REF_invokeSpecial;
        } else if (declaringKlass.isInterface()) {
            this.refKind = REF_invokeInterface;
        } else {
            assert !declaringKlass.isPrimitive();
            this.refKind = REF_invokeVirtual;
        }
    }

    public final Attribute getAttribute(Symbol<Name> attrName) {
        return linkedMethod.getAttribute(attrName);
    }

    @TruffleBoundary
    public final int BCItoLineNumber(int atBCI) {
        return codeAttribute.BCItoLineNumber(atBCI);
    }

    @Override
    public EspressoContext getContext() {
        return declaringKlass.getContext();
    }

    public final BootstrapMethodsAttribute getBootstrapMethods() {
        return (BootstrapMethodsAttribute) getAttribute(BootstrapMethodsAttribute.NAME);
    }

    public byte[] getCode() {
        return codeAttribute.getCode();
    }

    public CodeAttribute getCodeAttribute() {
        return codeAttribute;
    }

    public int getCodeSize() {
        return getCode() != null ? getCode().length : 0;
    }

    public int getMaxLocals() {
        return codeAttribute.getMaxLocals();
    }

    public int getMaxStackSize() {
        return codeAttribute.getMaxStack();
    }

    public ExceptionHandler[] getExceptionHandlers() {
        return codeAttribute.getExceptionHandlers();
    }

    private static String buildJniNativeSignature(Method method) {
        // Prepend JNIEnv*.
        StringBuilder sb = new StringBuilder("(").append(NativeSimpleType.SINT64);
        final Symbol<Type>[] signature = method.getParsedSignature();

        // Receiver for instance methods, class for static methods.
        sb.append(", ").append(NativeSimpleType.NULLABLE);

        int argCount = Signatures.parameterCount(signature, false);
        for (int i = 0; i < argCount; ++i) {
            sb.append(", ").append(Utils.kindToType(Signatures.parameterKind(signature, i), true));
        }

        sb.append("): ").append(Utils.kindToType(Signatures.returnKind(signature), false));

        return sb.toString();
    }

    private static TruffleObject bind(TruffleObject library, Method m, String mangledName) throws UnknownIdentifierException {
        String signature = buildJniNativeSignature(m);
        return NativeLibrary.lookupAndBind(library, mangledName, signature);
    }

    private static TruffleObject bind(TruffleObject symbol, Method m) {
        String signature = buildJniNativeSignature(m);
        return NativeLibrary.bind(symbol, signature);
    }

    /**
     * Ensure any callTarget is called immediately before a BCI is advanced, or it could violate the
     * specs on class init.
     */
    @TruffleBoundary
    public CallTarget getCallTarget() {
        if (callTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            if (poisonPill) {
                getMeta().throwExWithMessage(IncompatibleClassChangeError.class, "Conflicting default methods: " + this.getName());
            }
            // Initializing a class costs a lock, do it outside of this method's lock to avoid
            // congestion.
            // Note that requesting a call target is immediately followed by a call to the method,
            // before advancing BCI.
            // This ensures that we are respecting the specs, saying that a class must be
            // initialized before a method is called, while saving a call to safeInitialize after a
            // method lookup.
            declaringKlass.safeInitialize();

            synchronized (this) {
                if (callTarget != null) {
                    return callTarget;
                }
                if (proxy != null) {
                    this.callTarget = proxy.getCallTarget();
                    return callTarget;
                }
                EspressoRootNode redirectedMethod = getSubstitutions().get(this);
                if (redirectedMethod != null) {
                    callTarget = Truffle.getRuntime().createCallTarget(redirectedMethod);
                } else {
                    if (this.isNative()) {
                        // Bind native method.
                        // System.err.println("Linking native method: " +
                        // meta(this).getDeclaringClass().getName() + "#" + getName() + " " +
                        // getSignature());

                        // If the loader is null we have a system class, so we attempt a lookup in
                        // the native Java library.
                        if (StaticObject.isNull(getDeclaringKlass().getDefiningClassLoader())) {
                            // Look in libjava
                            for (boolean withSignature : new boolean[]{false, true}) {
                                String mangledName = Mangle.mangleMethod(this, withSignature);

                                try {
                                    TruffleObject nativeMethod = bind(getVM().getJavaLibrary(), this, mangledName);
                                    callTarget = Truffle.getRuntime().createCallTarget(new EspressoRootNode(this, new NativeRootNode(nativeMethod, this, true)));
                                    return callTarget;
                                } catch (UnknownIdentifierException e) {
                                    // native method not found in libjava, safe to ignore
                                }
                            }
                        }

                        Method findNative = getMeta().ClassLoader_findNative;

                        // Lookup the short name first, otherwise lookup the long name (with
                        // signature).
                        callTarget = lookupJniCallTarget(findNative, false);
                        if (callTarget == null) {
                            callTarget = lookupJniCallTarget(findNative, true);
                        }

                        // TODO(peterssen): Search JNI methods with OS prefix/suffix
                        // (print_jni_name_suffix_on ...)

                        if (callTarget == null) {
                            if (getDeclaringKlass() == getMeta().MethodHandle && (getName() == Name.invokeExact || getName() == Name.invoke)) {
                                this.callTarget = declaringKlass.lookupPolysigMethod(getName(), getRawSignature()).getCallTarget();
                            } else {
                                System.err.println("Failed to link native method: " + getDeclaringKlass().getType() + "." + getName() + " -> " + getRawSignature());
                                throw getMeta().throwEx(UnsatisfiedLinkError.class);
                            }
                        }
                    } else {
                        FrameDescriptor frameDescriptor = initFrameDescriptor(getMaxLocals() + getMaxStackSize());
                        EspressoRootNode rootNode = new EspressoRootNode(this, frameDescriptor, new BytecodeNode(this, frameDescriptor));
                        callTarget = Truffle.getRuntime().createCallTarget(rootNode);
                    }
                }
            }
        }

        return callTarget;
    }

    public static FrameDescriptor initFrameDescriptor(int slotCount) {
        FrameDescriptor descriptor = new FrameDescriptor();
        for (int i = 0; i < slotCount; ++i) {
            descriptor.addFrameSlot(i);
        }
        return descriptor;
    }

    private CallTarget lookupJniCallTarget(Method findNative, boolean fullSignature) {
        String mangledName = Mangle.mangleMethod(this, fullSignature);
        long handle = (long) findNative.invokeWithConversions(null, getDeclaringKlass().getDefiningClassLoader(), mangledName);
        if (handle == 0) { // not found
            return null;
        }
        TruffleObject symbol = getVM().getFunction(handle);
        TruffleObject nativeMethod = bind(symbol, this);
        return Truffle.getRuntime().createCallTarget(new EspressoRootNode(this, new NativeRootNode(nativeMethod, this, true)));
    }

    public boolean isConstructor() {
        return Name.INIT.equals(getName());
    }

    public boolean isDefault() {
        if (isConstructor()) {
            return false;
        }
        // Copied from java.lang.Method.isDefault()
        int mask = Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC;
        return ((getModifiers() & mask) == Modifier.PUBLIC) && getDeclaringKlass().isInterface();
    }

    public ObjectKlass[] getCheckedExceptions() {
        if (checkedExceptions == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            createCheckedExceptions();
        }
        return checkedExceptions;
    }

    private synchronized void createCheckedExceptions() {
        if (checkedExceptions == null) {
            if (exceptionsAttribute == null) {
                checkedExceptions = ObjectKlass.EMPTY_ARRAY;
                return;
            }
            final int[] entries = exceptionsAttribute.getCheckedExceptionsCPI();
            ObjectKlass[] tmpchecked = new ObjectKlass[entries.length];
            for (int i = 0; i < entries.length; ++i) {
                // getConstantPool().classAt(entries[i]).
                // TODO(peterssen): Resolve and cache CP entries.
                tmpchecked[i] = (ObjectKlass) ((RuntimeConstantPool) getDeclaringKlass().getConstantPool()).resolvedKlassAt(getDeclaringKlass(), entries[i]);
            }
            checkedExceptions = tmpchecked;
        }
    }

    public boolean isFinal() {
        return ModifiersProvider.super.isFinalFlagSet();
    }

    /**
     * Checks whether the method has bytecodes associated with it. Methods without bytecodes are
     * either abstract or native methods.
     *
     * @return whether the definition of this method is Java bytecodes
     */
    public boolean hasBytecodes() {
        return isConcrete() && !isNative();
    }

    /**
     * Checks whether the method has a receiver parameter - i.e., whether it is not static.
     *
     * @return whether the method has a receiver parameter
     */
    public boolean hasReceiver() {
        return !isStatic();
    }

    /**
     * Determines if this method is {@link java.lang.Object#Object()}.
     */
    public boolean isJavaLangObjectInit() {
        return getDeclaringKlass().isJavaLangObject() && Name.INIT.equals(getName());
    }

    // region Meta.Method

    /**
     * Invoke guest method, parameters and return value are converted to host world. Primitives,
     * primitive arrays are shared, and are passed verbatim, conversions are provided for String and
     * StaticObject.NULL/null. There's no parameter casting based on the method's signature,
     * widening nor narrowing.
     */
    @TruffleBoundary
    public Object invokeWithConversions(Object self, Object... args) {
        getContext().getJNI().clearPendingException();
        assert args.length == Signatures.parameterCount(getParsedSignature(), false);
        // assert !isStatic() || ((StaticObject) self).isStatic();

        final Object[] filteredArgs;
        if (isStatic()) {
            // getDeclaringKlass().safeInitialize();
            filteredArgs = new Object[args.length];
            for (int i = 0; i < filteredArgs.length; ++i) {
                filteredArgs[i] = getMeta().toGuestBoxed(args[i]);
            }
        } else {
            filteredArgs = new Object[args.length + 1];
            filteredArgs[0] = getMeta().toGuestBoxed(self);
            for (int i = 1; i < filteredArgs.length; ++i) {
                filteredArgs[i] = getMeta().toGuestBoxed(args[i - 1]);
            }
        }
        return getMeta().toHostBoxed(getCallTarget().call(filteredArgs));
    }

    /**
     * Invokes a guest method without parameter/return type conversions. There's no parameter
     * casting, widening nor narrowing based on the method signature.
     *
     * e.g. Host (boxed) Integer represents int, guest Integer doesn't.
     */
    @TruffleBoundary
    public Object invokeDirect(Object self, Object... args) {
        getContext().getJNI().clearPendingException();
        if (isStatic()) {
            assert args.length == Signatures.parameterCount(getParsedSignature(), false);
            // getDeclaringKlass().safeInitialize();
            return getCallTarget().call(args);
        } else {
            assert args.length + 1 /* self */ == Signatures.parameterCount(getParsedSignature(), !isStatic());
            Object[] fullArgs = new Object[args.length + 1];
            System.arraycopy(args, 0, fullArgs, 1, args.length);
            fullArgs[0] = self;
            return getCallTarget().call(fullArgs);
        }
    }

    public final boolean isClassInitializer() {
        return Name.CLINIT.equals(getName());
    }

    @Override
    public int getModifiers() {
        return linkedMethod.getFlags() & Constants.JVM_RECOGNIZED_METHOD_MODIFIERS;
    }

    @Override
    public String toString() {
        return "EspressoMethod<" + getDeclaringKlass().getType() + "." + getName() + " -> " + getRawSignature() + ">";
    }

    public final JavaKind getReturnKind() {
        return Signatures.returnKind(getParsedSignature());
    }

    public Klass[] resolveParameterKlasses() {
        // TODO(peterssen): Use resolved signature.
        final Symbol<Type>[] signature = getParsedSignature();
        int paramCount = Signatures.parameterCount(signature, false);
        Klass[] paramsKlasses = paramCount > 0 ? new Klass[paramCount] : Klass.EMPTY_ARRAY;
        for (int i = 0; i < paramCount; ++i) {
            Symbol<Type> paramType = Signatures.parameterType(signature, i);
            paramsKlasses[i] = getMeta().loadKlass(paramType, getDeclaringKlass().getDefiningClassLoader());
        }
        return paramsKlasses;
    }

    public Klass resolveReturnKlass() {
        // TODO(peterssen): Use resolved signature.
        Symbol<Type> returnType = Signatures.returnType(getParsedSignature());
        return getMeta().loadKlass(returnType, getDeclaringKlass().getDefiningClassLoader());
    }

    public int getParameterCount() {
        return Signatures.parameterCount(getParsedSignature(), false);
    }

    public static Method getHostReflectiveMethodRoot(StaticObject seed) {
        Meta meta = seed.getKlass().getMeta();
        StaticObject curMethod = seed;
        Method target = null;
        while (target == null) {
            target = (Method) curMethod.getHiddenField(meta.HIDDEN_METHOD_KEY);
            if (target == null) {
                curMethod = (StaticObject) meta.Method_root.get(curMethod);
            }
        }
        return target;
    }

    // Polymorphic signature method 'creation'

    final Method findIntrinsic(Symbol<Signature> signature, Function<Method, EspressoBaseNode> baseNodeFactory, MethodHandleIntrinsics.PolySigIntrinsics id) {
        return getContext().getMethodHandleIntrinsics().findIntrinsic(this, signature, baseNodeFactory, id);
    }

    final void setVTableIndex(int i) {
        assert (vtableIndex == -1 || vtableIndex == i);
        this.vtableIndex = i;
    }

    final public int getVTableIndex() {
        return vtableIndex;
    }

    final void setITableIndex(int i) {
        assert (itableIndex == -1 || itableIndex == i);
        this.itableIndex = i;
    }

    final public int getITableIndex() {
        return itableIndex;
    }

    public final boolean hasCode() {
        return codeAttribute != null || isNative();
    }

    public final boolean isVirtualCall() {
        return !isStatic() && !isConstructor() && !isPrivate() && !getDeclaringKlass().isInterface();
    }

    public static Method createIntrinsic(Method m, Symbol<Signature> rawSignature, Function<Method, EspressoBaseNode> baseNodeFactory) {
        assert (m.declaringKlass == m.getMeta().MethodHandle);
        Method method = new Method(m.declaringKlass, m.linkedMethod, rawSignature);
        EspressoRootNode rootNode = new EspressoRootNode(method, baseNodeFactory.apply(method));
        method.callTarget = Truffle.getRuntime().createCallTarget(rootNode);
        return method;
    }

    public void setPoisonPill() {
        this.poisonPill = true;
    }

    private String getSourceFile() {
        SourceFileAttribute sfa = (SourceFileAttribute) declaringKlass.getAttribute(Name.SourceFile);
        if (sfa == null) {
            return "unknown source";
        }
        return declaringKlass.getConstantPool().utf8At(sfa.getSourceFileIndex()).toString();
    }

    public final String report(int curBCI) {
        return "at " + MetaUtil.internalNameToJava(getDeclaringKlass().getType().toString(), true, false) + "." + getName() + "(" + getSourceFile() + ":" + BCItoLineNumber(curBCI) + ")";
    }

    public final String report() {
        return "at " + MetaUtil.internalNameToJava(getDeclaringKlass().getType().toString(), true, false) + "." + getName() + "(unknown source)";
    }

    public final ForeignAccess getForeignAccess() {
        return EspressoMethodMessageResolutionForeign.ACCESS;
    }
}
