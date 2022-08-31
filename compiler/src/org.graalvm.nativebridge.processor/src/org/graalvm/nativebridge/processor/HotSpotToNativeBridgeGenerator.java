/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.nativebridge.processor;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.graalvm.nativebridge.processor.AbstractBridgeParser.CacheData;
import org.graalvm.nativebridge.processor.AbstractBridgeParser.DefinitionData;
import org.graalvm.nativebridge.processor.AbstractBridgeParser.MarshallerData;
import org.graalvm.nativebridge.processor.AbstractBridgeParser.MethodData;
import org.graalvm.nativebridge.processor.HotSpotToNativeBridgeParser.HotSpotToNativeDefinitionData;
import org.graalvm.nativebridge.processor.HotSpotToNativeBridgeParser.TypeCache;

final class HotSpotToNativeBridgeGenerator extends AbstractBridgeGenerator {

    static final String START_POINT_FACTORY_NAME = "createHotSpotToNative";
    private static final String START_POINT_SIMPLE_NAME = "HotSpotToNativeStartPoint";
    private static final String END_POINT_SIMPLE_NAME = "HotSpotToNativeEndPoint";
    static final String SHARED_START_POINT_SIMPLE_NAME = "StartPoint";
    static final String SHARED_END_POINT_SIMPLE_NAME = "EndPoint";

    private final TypeCache typeCache;
    private final String[] sharedNames;
    private final String[] unsharedNames;
    private FactoryMethodInfo factoryMethod;

    HotSpotToNativeBridgeGenerator(AbstractBridgeParser parser, TypeCache typeCache, DefinitionData definitionData) {
        this(parser, typeCache, definitionData, new String[]{SHARED_START_POINT_SIMPLE_NAME, SHARED_END_POINT_SIMPLE_NAME},
                        new String[]{START_POINT_SIMPLE_NAME, END_POINT_SIMPLE_NAME});
    }

    HotSpotToNativeBridgeGenerator(AbstractBridgeParser parser, TypeCache typeCache, DefinitionData definitionData,
                    String[] sharedNames, String[] unsharedNames) {
        super(parser, definitionData, typeCache);
        assert sharedNames.length == 2 : "Names must be an array of the form `{startPointName, endPointName}`";
        assert unsharedNames.length == 2 : "Names must be an array of the form `{startPointName, endPointName}`";
        this.typeCache = typeCache;
        this.sharedNames = sharedNames;
        this.unsharedNames = unsharedNames;
        this.factoryMethod = resolveFactoryMethod(START_POINT_FACTORY_NAME, unsharedNames[0], unsharedNames[1]);
    }

    void setShared(boolean shared) {
        if (shared) {
            this.factoryMethod = new FactoryMethodInfo(START_POINT_FACTORY_NAME, sharedNames[0], sharedNames[1], factoryMethod.parameters, factoryMethod.superCallParameters);
        } else {
            this.factoryMethod = new FactoryMethodInfo(START_POINT_FACTORY_NAME, unsharedNames[0], unsharedNames[1], factoryMethod.parameters, factoryMethod.superCallParameters);
        }
    }

    @Override
    void configureMultipleDefinitions(List<DefinitionData> otherDefinitions) {
        Optional<DefinitionData> hotSpotToNativeOrNull = otherDefinitions.stream().filter((d) -> d instanceof HotSpotToNativeDefinitionData).findAny();
        boolean shared = hotSpotToNativeOrNull.isPresent() &&
                        NativeToNativeBridgeGenerator.isCompatible(types, (HotSpotToNativeDefinitionData) definitionData, (HotSpotToNativeDefinitionData) hotSpotToNativeOrNull.get());
        setShared(shared);
    }

    @Override
    void generateAPI(CodeBuilder builder, CharSequence targetClassSimpleName) {
        builder.lineEnd("");
        generateStartPointFactory(builder, factoryMethod);
    }

    @Override
    void generateImpl(CodeBuilder builder, CharSequence targetClassSimpleName) {
        builder.lineEnd("");
        generateHSToNativeStartPoint(builder, factoryMethod);
        builder.lineEnd("");
        generateHSToNativeEndPoint(builder, targetClassSimpleName);
    }

    @Override
    HotSpotToNativeMarshallerSnippets marshallerSnippets(MarshallerData marshallerData) {
        return HotSpotToNativeMarshallerSnippets.forData(parser.processor, definitionData, marshallerData, types, typeCache);
    }

    private void generateHSToNativeStartPoint(CodeBuilder builder, FactoryMethodInfo factoryMethodInfo) {
        HotSpotToNativeDefinitionData hsData = ((HotSpotToNativeDefinitionData) definitionData);

        CacheSnippets cacheSnippets = cacheSnippets();
        builder.classStart(EnumSet.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL), factoryMethod.startPointSimpleName,
                        definitionData.annotatedType, Collections.emptyList());
        builder.indent();
        builder.lineEnd("");

        generateMarshallerFields(builder, Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC);
        builder.line("");

        if (!definitionData.getAllCustomMarshallers().isEmpty()) {
            builder.methodStart(EnumSet.of(Modifier.STATIC), null,
                            null, Collections.emptyList(), Collections.emptyList());
            builder.indent();
            builder.lineStart().write(typeCache.jniConfig).write(" config = ").invokeStatic(hsData.jniConfig, "getInstance").lineEnd(";");
            generateMarshallerLookups(builder);
            builder.dedent();
            builder.line("}");
        }

        generateCacheFields(builder, cacheSnippets);
        builder.lineEnd("");
        builder.methodStart(Collections.emptySet(), factoryMethod.startPointSimpleName, null,
                        factoryMethodInfo.parameters, Collections.emptyList());
        builder.indent();
        builder.lineStart().call("super", parameterNames(factoryMethodInfo.superCallParameters)).lineEnd(";");
        generateCacheFieldsInit(builder, cacheSnippets);
        builder.dedent();
        builder.line("}");

        for (MethodData methodData : definitionData.toGenerate) {
            generateHSToNativeStartMethod(builder, cacheSnippets, methodData);
        }
        for (MethodData methodData : definitionData.toGenerate) {
            generateHSToNativeNativeMethod(builder, methodData);
        }

        builder.dedent();
        builder.classEnd();
    }

    private void generateHSToNativeStartMethod(CodeBuilder builder, CacheSnippets cacheSnippets, MethodData methodData) {
        builder.line("");
        overrideMethod(builder, methodData);
        builder.indent();
        CodeBuilder receiverCastStatement;
        CharSequence receiver;
        CharSequence receiverNativeObject;
        int nonReceiverParameterStart;
        if (definitionData.hasCustomDispatch()) {
            receiver = methodData.element.getParameters().get(0).getSimpleName();
            receiverCastStatement = new CodeBuilder(builder).write(typeCache.nativeObject).write(" nativeObject = (").write(typeCache.nativeObject).write(") ").write(receiver).write(";");
            receiverNativeObject = "nativeObject";
            nonReceiverParameterStart = 1;
        } else {
            receiver = definitionData.endPointHandle != null ? definitionData.endPointHandle.getSimpleName() : "this";
            receiverCastStatement = null;
            receiverNativeObject = receiver;
            nonReceiverParameterStart = 0;
        }
        CharSequence scopeVarName = "nativeIsolateThread";
        CodeBuilder getIsolateCall = new CodeBuilder(builder).invoke(receiverNativeObject, "getIsolate");
        CodeBuilder enterScope = new CodeBuilder(builder).write(typeCache.nativeIsolateThread).space().write(scopeVarName).write(" = ").invoke(getIsolateCall.build(), "enter").write(";");
        List<CharSequence> actualParameters = new ArrayList<>();
        actualParameters.add(new CodeBuilder(builder).invoke(scopeVarName, "getIsolateThreadId").build());
        actualParameters.add(new CodeBuilder(builder).invoke(receiverNativeObject, "getHandle").build());
        List<? extends VariableElement> formalParameters = methodData.element.getParameters();
        List<? extends TypeMirror> formalParameterTypes = methodData.type.getParameterTypes();
        CharSequence marshalledParametersOutput = null;
        List<MarshalledParameter> marshallParameters = new ArrayList<>();
        for (int i = nonReceiverParameterStart; i < formalParameters.size(); i++) {
            MarshallerData marshaller = methodData.getParameterMarshaller(i);
            TypeMirror parameterType = formalParameterTypes.get(i);
            if (isBinaryMarshallable(marshaller, parameterType)) {
                marshalledParametersOutput = "marshalledParametersOutput";
                CharSequence parameterName = formalParameters.get(i).getSimpleName();
                marshallParameters.add(new MarshalledParameter(parameterName, parameterType, marshaller));
            } else {
                actualParameters.add(marshallerSnippets(marshaller).marshallParameter(builder,
                                parameterType, formalParameters.get(i).getSimpleName(), null, null));
            }
        }
        if (marshalledParametersOutput != null) {
            actualParameters.add(new CodeBuilder(builder).invoke(marshalledParametersOutput, "getArray").build());
        }
        String nativeMethodName = jniMethodName(methodData.element);
        CharSequence nativeCall = new CodeBuilder(builder).call(
                        nativeMethodName, actualParameters.toArray(new CharSequence[0])).build();
        CacheData cacheData = methodData.cachedData;
        TypeMirror returnType = methodData.type.getReturnType();
        if (cacheData != null) {
            String cacheFieldName = cacheData.cacheFieldName;
            String resFieldName = cacheFieldName + "Result";
            builder.lineStart().write(cacheData.cacheEntryType).space().write(resFieldName).write(" = ").write(cacheSnippets.readCache(builder, cacheFieldName, receiver)).lineEnd(";");
            builder.lineStart("if (").write(resFieldName).lineEnd(" == null) {");
            builder.indent();
            if (receiverCastStatement != null) {
                builder.line(receiverCastStatement.build());
            }
            builder.line(enterScope.build());
            builder.line("try {");
            builder.indent();
            generateByteArrayBinaryOutputInit(builder, marshalledParametersOutput, marshallParameters);
            generateMarshallParameters(builder, marshallParameters, marshalledParametersOutput);
            MarshallerSnippets marshallerSnippets = marshallerSnippets(methodData.getReturnTypeMarshaller());
            CharSequence endPointResultVariable = marshallerSnippets.preUnmarshallResult(builder, returnType, nativeCall, receiverNativeObject, null);
            CharSequence value = marshallerSnippets.unmarshallResult(builder, returnType,
                            endPointResultVariable != null ? endPointResultVariable : nativeCall, receiverNativeObject, null, null);
            builder.lineStart().write(resFieldName).write(" = ").write(value).lineEnd(";");
            builder.lineStart().write(cacheSnippets.writeCache(builder, cacheFieldName, receiver, resFieldName)).lineEnd(";");
            builder.dedent();
            generateHSToNativeStartPointExceptionHandlers(builder, scopeVarName);
            builder.dedent();
            builder.line("}");
            builder.lineStart("return ").write(resFieldName).lineEnd(";");
        } else {
            if (receiverCastStatement != null) {
                builder.line(receiverCastStatement.build());
            }
            builder.line(enterScope.build());
            builder.line("try {");
            builder.indent();
            generateByteArrayBinaryOutputInit(builder, marshalledParametersOutput, marshallParameters);
            generateMarshallParameters(builder, marshallParameters, marshalledParametersOutput);
            MarshallerSnippets marshallerSnippets = marshallerSnippets(methodData.getReturnTypeMarshaller());
            CharSequence endPointResultVariable = marshallerSnippets.preUnmarshallResult(builder, returnType, nativeCall, receiverNativeObject, null);
            builder.lineStart();
            if (returnType.getKind() != TypeKind.VOID) {
                builder.write("return ");
            }
            CharSequence value = marshallerSnippets.unmarshallResult(builder, returnType,
                            endPointResultVariable != null ? endPointResultVariable : nativeCall, receiverNativeObject, null, null);
            builder.write(value);
            builder.lineEnd(";");
            builder.dedent();
            generateHSToNativeStartPointExceptionHandlers(builder, scopeVarName);
        }
        builder.dedent();
        builder.line("}");
    }

    private static boolean isBinaryMarshallable(MarshallerData marshaller, TypeMirror type) {
        if (marshaller.isCustom()) {
            // Custom type is always marshalled
            return true;
        } else if (marshaller.isReference() && marshaller.sameDirection && type.getKind() == TypeKind.ARRAY) {
            // Arrays of NativeObject references (long handles) are marshalled
            return true;
        } else {
            return false;
        }
    }

    private void generateHSToNativeStartPointExceptionHandlers(CodeBuilder builder, CharSequence scopeVarName) {
        CharSequence foreignException = "foreignException";
        CharSequence throwUnboxedException = new CodeBuilder(builder).write("throw").space().invoke(foreignException, "throwOriginalException",
                        definitionData.getCustomMarshaller(typeCache.throwable, null, types).name).write(";").build();
        builder.lineStart("} catch (").write(typeCache.foreignException).space().write(foreignException).write(") ").lineEnd("{");
        builder.indent();
        builder.line(throwUnboxedException);
        builder.dedent();
        builder.line("} finally {");
        builder.indent();
        builder.lineStart().invoke(scopeVarName, "leave").lineEnd(";");
        builder.dedent();
        builder.line("}");
    }

    private void generateMarshallParameters(CodeBuilder builder, List<MarshalledParameter> marshalledParameters, CharSequence marshalledParametersOutput) {
        for (MarshalledParameter marshalledParameter : marshalledParameters) {
            MarshallerSnippets snippets = marshallerSnippets(marshalledParameter.marshallerData);
            snippets.preMarshallParameter(builder, marshalledParameter.parameterType, marshalledParameter.parameterName, marshalledParametersOutput, null, new HashMap<>());
            CharSequence expr = snippets.marshallParameter(builder, marshalledParameter.parameterType, marshalledParameter.parameterName, marshalledParametersOutput, null);
            if (expr != null) {
                builder.lineStart(expr).lineEnd(";");
            }
        }
    }

    private void generateByteArrayBinaryOutputInit(CodeBuilder builder, CharSequence marshalledParametersOutputVar, List<MarshalledParameter> marshalledParameters) {
        if (marshalledParametersOutputVar != null) {
            CharSequence sizeVar = "marshalledParametersSizeEstimate";
            generateSizeEstimate(builder, sizeVar, marshalledParameters);
            builder.lineStart().write(typeCache.byteArrayBinaryOutput).space().write(marshalledParametersOutputVar).write(" = ").invokeStatic(typeCache.byteArrayBinaryOutput, "create",
                            sizeVar).lineEnd(";");
        }
    }

    private CharSequence generateCCharPointerBinaryOutputInit(CodeBuilder builder, boolean hasMarshalledArgs, CharSequence marshalledResultOutputVar,
                    MarshallerData marshallerData, CharSequence endPointResultVar,
                    CharSequence marshallBufferVar, CharSequence staticMarshallBufferVar, CharSequence marshalledDataLengthVar, CharSequence staticBufferSize) {
        CharSequence sizeEstimateVar = "marshalledResultSizeEstimate";
        generateSizeEstimate(builder, sizeEstimateVar, Collections.singletonList(new MarshalledParameter(endPointResultVar, null, marshallerData)));
        CharSequence marshallBufferLengthVar = "marshallBufferLength";
        if (hasMarshalledArgs) {
            builder.lineStart().write(types.getPrimitiveType(TypeKind.INT)).space().write(marshallBufferLengthVar).write(" = ").invokeStatic(typeCache.math, "max", staticBufferSize,
                            marshalledDataLengthVar).lineEnd(";");
        }
        CharSequence preAllocatedSizeVar = hasMarshalledArgs ? marshallBufferLengthVar : staticBufferSize;
        CharSequence[] preAllocatedArgs = {
                        hasMarshalledArgs ? marshallBufferVar : staticMarshallBufferVar,
                        preAllocatedSizeVar,
                        "false"
        };
        CodeBuilder code = new CodeBuilder(builder).write(typeCache.cCharPointerBinaryOutput).space().write(marshalledResultOutputVar).write(" = ").write(sizeEstimateVar).write(" > ").write(
                        preAllocatedSizeVar).write(" ? ").invokeStatic(typeCache.cCharPointerBinaryOutput, "create", sizeEstimateVar).write(" : ").invokeStatic(typeCache.binaryOutput, "create",
                                        preAllocatedArgs);
        return code.build();
    }

    private void generateHSToNativeNativeMethod(CodeBuilder builder, MethodData methodData) {
        builder.line("");
        String nativeMethodName = jniMethodName(methodData.element);
        TypeMirror nativeMethodReturnType = marshallerSnippets(methodData.getReturnTypeMarshaller()).getEndPointMethodParameterType(methodData.type.getReturnType());
        List<CodeBuilder.Parameter> nativeMethodParameters = new ArrayList<>();
        PrimitiveType longType = types.getPrimitiveType(TypeKind.LONG);
        nativeMethodParameters.add(CodeBuilder.newParameter(longType, "isolateThread"));
        nativeMethodParameters.add(CodeBuilder.newParameter(longType, "objectId"));
        int nonReceiverParameterStart = definitionData.hasCustomDispatch() ? 1 : 0;
        List<? extends VariableElement> parameters = methodData.element.getParameters();
        List<? extends TypeMirror> parameterTypes = methodData.type.getParameterTypes();
        boolean hasMarshallerData = false;
        for (int i = nonReceiverParameterStart; i < parameters.size(); i++) {
            MarshallerData marshallerData = methodData.getParameterMarshaller(i);
            if (isBinaryMarshallable(marshallerData, parameterTypes.get(i))) {
                hasMarshallerData = true;
            } else {
                TypeMirror nativeMethodParameter = marshallerSnippets(marshallerData).getEndPointMethodParameterType(parameterTypes.get(i));
                nativeMethodParameters.add(CodeBuilder.newParameter(nativeMethodParameter, parameters.get(i).getSimpleName()));
            }
        }
        if (hasMarshallerData) {
            nativeMethodParameters.add(CodeBuilder.newParameter(types.getArrayType(types.getPrimitiveType(TypeKind.BYTE)), MARSHALLED_DATA_PARAMETER));
        }
        builder.methodStart(EnumSet.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.NATIVE),
                        nativeMethodName,
                        nativeMethodReturnType,
                        nativeMethodParameters,
                        methodData.type.getThrownTypes());
    }

    private void generateHSToNativeEndPoint(CodeBuilder builder, CharSequence targetClassSimpleName) {
        HotSpotToNativeDefinitionData hsData = ((HotSpotToNativeDefinitionData) definitionData);

        builder.lineStart().annotation(typeCache.suppressWarnings, "unused").lineEnd("");
        builder.classStart(EnumSet.of(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL), factoryMethod.endPointSimpleName, null, Collections.emptyList());
        builder.indent();
        builder.lineEnd("");

        generateMarshallerFields(builder, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL);
        builder.line("");

        if (!definitionData.getAllCustomMarshallers().isEmpty()) {
            builder.methodStart(EnumSet.of(Modifier.STATIC), null,
                            null, Collections.emptyList(), Collections.emptyList());
            builder.indent();
            builder.lineStart().write(typeCache.jniConfig).write(" config = ").invokeStatic(hsData.jniConfig, "getInstance").lineEnd(";");
            generateMarshallerLookups(builder);
            builder.dedent();
            builder.line("}");
            builder.line("");
        }

        for (MethodData methodData : definitionData.toGenerate) {
            String entryPointSymbolName = jniCMethodSymbol(methodData, targetClassSimpleName);
            generateHSToNativeEndMethod(builder, methodData, entryPointSymbolName, targetClassSimpleName);
        }
        builder.dedent();
        builder.classEnd();
    }

    private void generateHSToNativeEndMethod(CodeBuilder builder, MethodData methodData, String entryPointName, CharSequence targetClassSimpleName) {
        builder.lineEnd("");
        Map<String, Object> centryPointAttrs = new LinkedHashMap<>();
        centryPointAttrs.put("name", entryPointName);
        DeclaredType centryPointPredicate = ((HotSpotToNativeBridgeParser.HotSpotToNativeDefinitionData) definitionData).centryPointPredicate;
        if (centryPointPredicate != null) {
            centryPointAttrs.put("include", centryPointPredicate);
        }
        builder.lineStart().annotationWithAttributes(typeCache.centryPoint, centryPointAttrs).lineEnd("");
        List<? extends VariableElement> methodParameters = methodData.element.getParameters();
        List<? extends TypeMirror> methodParameterTypes = methodData.type.getParameterTypes();
        Set<CharSequence> warnings = new TreeSet<>(Comparator.comparing(CharSequence::toString));
        Collections.addAll(warnings, "try", "unused");
        if (!definitionData.hasCustomDispatch() && isParameterizedType(definitionData.serviceType)) {
            warnings.add("unchecked");
        }
        for (int i = 0; i < methodParameters.size(); i++) {
            warnings.addAll(marshallerSnippets(methodData.getParameterMarshaller(i)).getEndPointSuppressedWarnings(builder, methodParameterTypes.get(i)));
        }
        builder.lineStart().annotation(typeCache.suppressWarnings, warnings.toArray(new CharSequence[0])).lineEnd("");
        List<CodeBuilder.Parameter> params = new ArrayList<>();
        CharSequence jniEnvVariable = "jniEnv";
        params.add(CodeBuilder.newParameter(typeCache.jniEnv, jniEnvVariable));
        params.add(CodeBuilder.newParameter(typeCache.jClass, "jniClazz"));
        CodeBuilder isolateAnnotationBuilder = new CodeBuilder(builder);
        isolateAnnotationBuilder.annotation(typeCache.isolateThreadContext, null);
        params.add(CodeBuilder.newParameter(types.getPrimitiveType(TypeKind.LONG), "isolateThread", isolateAnnotationBuilder.build()));
        params.add(CodeBuilder.newParameter(types.getPrimitiveType(TypeKind.LONG), "objectId"));
        int parameterStartIndex = definitionData.hasCustomDispatch() ? 1 : 0;
        int marshalledDataCount = 0;
        for (int i = parameterStartIndex; i < methodParameters.size(); i++) {
            MarshallerData marshalledData = methodData.getParameterMarshaller(i);
            if (isBinaryMarshallable(marshalledData, methodParameterTypes.get(i))) {
                marshalledDataCount++;
            } else {
                TypeMirror nativeMethodType = marshallerSnippets(marshalledData).getEndPointMethodParameterType(methodParameterTypes.get(i));
                params.add(CodeBuilder.newParameter(jniTypeForJavaType(nativeMethodType, types, typeCache), methodParameters.get(i).getSimpleName()));
            }
        }
        if (marshalledDataCount > 0) {
            params.add(CodeBuilder.newParameter(typeCache.jByteArray, MARSHALLED_DATA_PARAMETER));
        }
        CharSequence methodName = methodData.element.getSimpleName();
        HotSpotToNativeMarshallerSnippets returnTypeSnippets = marshallerSnippets(methodData.getReturnTypeMarshaller());
        TypeMirror returnType = returnTypeSnippets.getEndPointMethodParameterType(methodData.type.getReturnType());
        boolean voidReturnType = returnType.getKind() == TypeKind.VOID;
        boolean primitiveReturnType = returnType.getKind().isPrimitive();
        boolean objectReturnType = !(primitiveReturnType || voidReturnType);
        builder.methodStart(EnumSet.of(Modifier.STATIC), methodName, jniTypeForJavaType(returnType, types, typeCache), params, Collections.emptyList());
        builder.indent();
        String scopeName = targetClassSimpleName + "::" + methodName;
        String scopeInit = new CodeBuilder(builder).write(typeCache.jniMethodScope).write(" scope = ").newInstance(typeCache.jniMethodScope, "\"" + scopeName + "\"", params.get(0).name).build();
        if (objectReturnType) {
            builder.lineStart(scopeInit).lineEnd(";");
            scopeInit = new CodeBuilder(builder).write(typeCache.jniMethodScope).write(" sc = scope").build();
        }
        builder.lineStart("try (").write(scopeInit).lineEnd(") {");
        builder.indent();

        CharSequence[] actualParameters = new CharSequence[methodParameters.size()];
        int nonReceiverParameterStart;
        if (definitionData.hasCustomDispatch()) {
            actualParameters[0] = "receiverObject";
            nonReceiverParameterStart = 1;
        } else {
            nonReceiverParameterStart = 0;
        }

        CharSequence resolvedDispatch;
        if (definitionData.hasCustomDispatch()) {
            TypeMirror receiverType = definitionData.customDispatchAccessor.getParameters().get(0).asType();
            CodeBuilder classLiteralBuilder = new CodeBuilder(builder).classLiteral(receiverType);
            CharSequence nativeObject = "nativeObject";
            resolvedDispatch = "resolvedDispatch";
            builder.lineStart().write(receiverType).space().write(nativeObject).write(" = ").invokeStatic(typeCache.nativeObjectHandles, "resolve", "objectId", classLiteralBuilder.build()).lineEnd(
                            ";");
            builder.lineStart().write(definitionData.serviceType).space().write(resolvedDispatch).write(" = ").invokeStatic(definitionData.annotatedType,
                            definitionData.customDispatchAccessor.getSimpleName(),
                            nativeObject).lineEnd(
                                            ";");
            builder.lineStart().write(typeCache.object).space().write("receiverObject").write(" = ").invokeStatic(definitionData.annotatedType, definitionData.customReceiverAccessor.getSimpleName(),
                            nativeObject).lineEnd(
                                            ";");
        } else {
            resolvedDispatch = "receiverObject";
            CodeBuilder classLiteralBuilder = new CodeBuilder(builder).classLiteral(definitionData.serviceType);
            builder.lineStart().write(definitionData.serviceType).write(" receiverObject = ").invokeStatic(typeCache.nativeObjectHandles, "resolve", "objectId", classLiteralBuilder.build()).lineEnd(
                            ";");
        }

        // Create binary input for marshalled parameters
        CharSequence marshalledParametersInputVar = "marshalledParametersInput";
        CharSequence staticMarshallBufferVar = "staticMarshallBuffer";
        CharSequence marshallBufferVar = "marshallBuffer";
        CharSequence marshalledDataLengthVar = "marshalledDataLength";
        CharSequence staticBufferSize = null;
        boolean marshalledResult = methodData.getReturnTypeMarshaller().isCustom();
        if (marshalledDataCount > 0 || marshalledResult) {
            staticBufferSize = Integer.toString(getStaticBufferSize(marshalledDataCount, marshalledResult));
            builder.lineStart().write(typeCache.cCharPointer).space().write(staticMarshallBufferVar).write(" = ").invokeStatic(typeCache.stackValue, "get", staticBufferSize).lineEnd(";");
        }
        if (marshalledDataCount > 0) {
            builder.lineStart().write(types.getPrimitiveType(TypeKind.INT)).space().write(marshalledDataLengthVar).write(" = ").invokeStatic(typeCache.jniUtil, "GetArrayLength", jniEnvVariable,
                            MARSHALLED_DATA_PARAMETER).lineEnd(";");
            builder.lineStart().write(typeCache.cCharPointer).space().write(marshallBufferVar).write(" = ").write(marshalledDataLengthVar).write(" <= ").write(staticBufferSize).write(" ? ").write(
                            staticMarshallBufferVar).write(" : ").invokeStatic(typeCache.unmanagedMemory, "malloc", marshalledDataLengthVar).lineEnd(";");
            builder.line("try {");
            builder.indent();
            builder.lineStart().invokeStatic(typeCache.jniUtil, "GetByteArrayRegion", jniEnvVariable, MARSHALLED_DATA_PARAMETER, "0", marshalledDataLengthVar, marshallBufferVar).lineEnd(";");
        }

        if (marshalledDataCount > 0) {
            builder.lineStart().write(typeCache.binaryInput).space().write(marshalledParametersInputVar).write(" = ").invokeStatic(typeCache.binaryInput, "create", marshallBufferVar,
                            marshalledDataLengthVar).lineEnd(";");
        }

        Map<String, CharSequence> parameterValueOverrides = new HashMap<>();
        // Generate pre unmarshall statements
        for (int i = nonReceiverParameterStart; i < methodParameters.size(); i++) {
            marshallerSnippets(methodData.getParameterMarshaller(i)).preUnmarshallParameter(builder, methodParameterTypes.get(i), methodParameters.get(i).getSimpleName(),
                            marshalledParametersInputVar, params.get(0).name, parameterValueOverrides);
        }

        // Decode arguments.
        for (int i = nonReceiverParameterStart; i < methodParameters.size(); i++) {
            CharSequence parameterName = methodParameters.get(i).getSimpleName();
            CharSequence parameterValueOverride = parameterValueOverrides.get(parameterName.toString());
            MarshallerData marshallerData = methodData.getParameterMarshaller(i);
            if (parameterValueOverride != null) {
                actualParameters[i] = parameterValueOverride;
            } else {
                TypeMirror parameterType = methodParameterTypes.get(i);
                actualParameters[i] = marshallerSnippets(marshallerData).unmarshallParameter(builder, parameterType, parameterName, marshalledParametersInputVar,
                                params.get(0).name);
            }
        }

        CharSequence resultSnippet = new CodeBuilder(builder).invoke(resolvedDispatch, methodData.receiverMethod != null ? methodData.receiverMethod : methodName, actualParameters).build();
        CharSequence resultVariable = null;
        if (voidReturnType) {
            builder.lineStart().write(resultSnippet).lineEnd(";");
        } else {
            resultVariable = "endPointResult";
            builder.lineStart().write(methodData.type.getReturnType()).space().write(resultVariable).write(" = ").write(resultSnippet).lineEnd(";");
        }

        // Generate post unmarshall statements.
        for (int i = nonReceiverParameterStart; i < methodParameters.size(); i++) {
            HotSpotToNativeMarshallerSnippets marshallerSnippets = marshallerSnippets(methodData.getParameterMarshaller(i));
            marshallerSnippets.postUnmarshallParameter(builder, methodParameterTypes.get(i), methodParameters.get(i).getSimpleName(), params.get(0).name, resultVariable);
        }
        // Do return for non void methods
        if (resultVariable != null) {
            CharSequence resultOverride = returnTypeSnippets.preMarshallResult(builder, methodData.type.getReturnType(), resultVariable, jniEnvVariable);
            if (resultOverride != null) {
                resultVariable = resultOverride;
            }
            CharSequence marshalledResultOutputVar = "marshalledResultOutput";
            resultSnippet = returnTypeSnippets.marshallResult(builder, methodData.type.getReturnType(), resultVariable, marshalledResultOutputVar, params.get(0).name);

            // Create binary output to marshall result
            if (methodData.getReturnTypeMarshaller().isCustom()) {
                CharSequence binaryOutputInit = generateCCharPointerBinaryOutputInit(builder, marshalledDataCount > 0, marshalledResultOutputVar,
                                methodData.getReturnTypeMarshaller(), resultVariable, marshallBufferVar, staticMarshallBufferVar, marshalledDataLengthVar, staticBufferSize);
                builder.lineStart("try (").write(binaryOutputInit).lineEnd(") {");
                builder.indent();
                builder.lineStart(resultSnippet).lineEnd(";");
                CharSequence posVar = "marshalledResultPosition";
                builder.lineStart().write(types.getPrimitiveType(TypeKind.INT)).space().write(posVar).write(" = ").invoke(marshalledResultOutputVar, "getPosition").lineEnd(";");
                resultSnippet = "marshalledResult";
                CharSequence jByteArrayInit = new CodeBuilder(builder).invokeStatic(typeCache.jniUtil, "NewByteArray", jniEnvVariable, posVar).build();
                builder.lineStart().write(typeCache.jByteArray).space().write(resultSnippet).write(" = ");
                if (marshalledDataCount > 0) {
                    builder.write(posVar).write(" <= ").write(marshalledDataLengthVar).write(" ? ").write(MARSHALLED_DATA_PARAMETER).write(" : ").write(jByteArrayInit).lineEnd(";");
                } else {
                    builder.write(jByteArrayInit).lineEnd(";");
                }
                CharSequence address = new CodeBuilder(builder).invoke(marshalledResultOutputVar, "getAddress").build();
                builder.lineStart().invokeStatic(typeCache.jniUtil, "SetByteArrayRegion", jniEnvVariable, resultSnippet, "0", posVar, address).lineEnd(";");
            }

            if (primitiveReturnType) {
                builder.lineStart("return ").write(resultSnippet).lineEnd(";");
            } else {
                builder.lineStart().invoke("scope", "setObjectResult", resultSnippet).lineEnd(";");
            }

            // Clean up binary output for result marshalling
            if (methodData.getReturnTypeMarshaller().isCustom()) {
                builder.dedent();
                builder.line("}");
            }
        }
        // Clean up binary input for marshalled parameters
        if (marshalledDataCount > 0) {
            builder.dedent();
            builder.line("} finally {");
            builder.indent();
            builder.lineStart("if (").write(marshallBufferVar).write(" != ").write(staticMarshallBufferVar).lineEnd(") {");
            builder.indent();
            builder.lineStart().invokeStatic(typeCache.unmanagedMemory, "free", marshallBufferVar).lineEnd(";");
            builder.dedent();
            builder.line("}");
            builder.dedent();
            builder.line("}");
        }
        builder.dedent();
        String exceptionVariable = "e";
        builder.lineStart("} catch (").write(typeCache.throwable).space().write(exceptionVariable).lineEnd(") {");
        builder.indent();
        CharSequence newForeignException = new CodeBuilder(builder).invokeStatic(typeCache.foreignException, "forThrowable",
                        exceptionVariable, definitionData.getCustomMarshaller(typeCache.throwable, null, types).name).build();
        builder.lineStart().invoke(newForeignException, "throwInHotSpot", jniEnvVariable).lineEnd(";");
        if (primitiveReturnType) {
            builder.lineStart("return ").writeDefaultValue(returnType).lineEnd(";");
        } else if (objectReturnType) {
            String cnull = new CodeBuilder(builder).invokeStatic(typeCache.wordFactory, "nullPointer").build();
            builder.lineStart().invoke("scope", "setObjectResult", cnull).lineEnd(";");
        }
        builder.dedent();
        builder.line("}");
        if (objectReturnType) {
            builder.lineStart("return ").invoke("scope", "getObjectResult").lineEnd(";");
        }
        builder.dedent();
        builder.line("}");
    }

    private static String jniMethodName(ExecutableElement methodElement) {
        return methodElement.getSimpleName() + "0";
    }

    private String jniCMethodSymbol(MethodData methodData, CharSequence targetClassSimpleName) {
        String packageName = Utilities.getEnclosingPackageElement((TypeElement) definitionData.annotatedType.asElement()).getQualifiedName().toString();
        String classSimpleName = targetClassSimpleName + "$" + factoryMethod.startPointSimpleName;
        String entryPointName = String.format("Java_%s_%s_%s",
                        Utilities.cSymbol(packageName),
                        Utilities.cSymbol(classSimpleName),
                        Utilities.cSymbol(jniMethodName(methodData.element)));
        if (methodData.hasOverload()) {
            StringBuilder sb = new StringBuilder(entryPointName);
            sb.append("__");
            encodeTypeForJniCMethodSymbol(sb, types.getPrimitiveType(TypeKind.LONG));   // Isolate
                                                                                        // thread
            encodeTypeForJniCMethodSymbol(sb, types.getPrimitiveType(TypeKind.LONG));   // Object
                                                                                        // handle
            List<? extends VariableElement> params = methodData.element.getParameters();
            int nonReceiverParameterStart = definitionData.hasCustomDispatch() ? 1 : 0;
            for (int i = nonReceiverParameterStart; i < params.size(); i++) {
                encodeTypeForJniCMethodSymbol(sb, types.erasure(params.get(i).asType()));
            }
            entryPointName = sb.toString();
        }
        return entryPointName;
    }

    private static void encodeTypeForJniCMethodSymbol(StringBuilder into, TypeMirror type) {
        Utilities.encodeType(into, type, "_3",
                        (te) -> "L" + Utilities.cSymbol(te.getQualifiedName().toString()) + "_2");
    }

    private abstract static class HotSpotToNativeMarshallerSnippets extends MarshallerSnippets {

        final TypeCache cache;

        HotSpotToNativeMarshallerSnippets(NativeBridgeProcessor processor, MarshallerData marshallerData, Types types, TypeCache typeCache) {
            super(processor, marshallerData, types, typeCache);
            this.cache = typeCache;
        }

        static HotSpotToNativeMarshallerSnippets forData(NativeBridgeProcessor processor, DefinitionData data, MarshallerData marshallerData, Types types, TypeCache typeCache) {
            switch (marshallerData.kind) {
                case VALUE:
                    return new DirectSnippets(processor, marshallerData, types, typeCache);
                case REFERENCE:
                    return new ReferenceSnippets(processor, data, marshallerData, types, typeCache);
                case RAW_REFERENCE:
                    return new RawReferenceSnippets(processor, marshallerData, types, typeCache);
                case CUSTOM:
                    return new CustomSnippets(processor, marshallerData, types, typeCache);
                default:
                    throw new IllegalArgumentException(String.valueOf(marshallerData.kind));
            }
        }

        private static final class DirectSnippets extends HotSpotToNativeMarshallerSnippets {

            DirectSnippets(NativeBridgeProcessor processor, MarshallerData marshallerData, Types types, TypeCache cache) {
                super(processor, marshallerData, types, cache);
            }

            @Override
            TypeMirror getEndPointMethodParameterType(TypeMirror type) {
                return type;
            }

            @Override
            CharSequence marshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence formalParameter, CharSequence marshalledParametersOutput, CharSequence jniEnvFieldName) {
                return formalParameter;
            }

            @Override
            CharSequence unmarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence receiver, CharSequence marshalledResultInput,
                            CharSequence jniEnvFieldName) {
                return invocationSnippet;
            }

            @Override
            CharSequence marshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence marshalledResultOutput, CharSequence jniEnvFieldName) {
                if (types.isSameType(cache.string, resultType)) {
                    return new CodeBuilder(currentBuilder).invokeStatic(cache.jniUtil, "createHSString", jniEnvFieldName, invocationSnippet).build();
                } else if (resultType.getKind() == TypeKind.ARRAY) {
                    return new CodeBuilder(currentBuilder).invokeStatic(cache.jniUtil, "createHSArray", jniEnvFieldName, invocationSnippet).build();
                } else {
                    return invocationSnippet;
                }
            }

            @Override
            CharSequence unmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence marshalledParametersInput, CharSequence jniEnvFieldName) {
                if (types.isSameType(cache.string, parameterType)) {
                    return new CodeBuilder(currentBuilder).invokeStatic(cache.jniUtil, "createString", jniEnvFieldName, parameterName).build();
                } else if (parameterType.getKind() == TypeKind.ARRAY) {
                    return new CodeBuilder(currentBuilder).invokeStatic(cache.jniUtil, "createArray", jniEnvFieldName, parameterName).build();
                } else {
                    return parameterName;
                }
            }

            @Override
            boolean preUnmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence marshalledParametersInput, CharSequence jniEnvFieldName,
                            Map<String, CharSequence> parameterValueOverride) {
                boolean hasDirectionModifiers = isArrayWithDirectionModifiers(parameterType);
                if (hasDirectionModifiers) {
                    AnnotationMirror in = findIn(marshallerData.annotations);
                    AnnotationMirror out = findOut(marshallerData.annotations);
                    currentBuilder.lineStart().write(parameterType).space().write(outArrayLocal(parameterName)).write(" = ");
                    if (in != null) {
                        CharSequence arrayLengthParameter = getArrayLengthParameterName(in);
                        if (arrayLengthParameter != null) {
                            currentBuilder.newArray(((ArrayType) parameterType).getComponentType(), arrayLengthParameter).lineEnd(";");
                            CharSequence arrayOffsetSnippet;
                            CharSequence arrayOffsetParameter = getArrayOffsetParameterName(in);
                            if (arrayOffsetParameter != null) {
                                arrayOffsetSnippet = arrayOffsetParameter;
                                parameterValueOverride.put(arrayOffsetParameter.toString(), "0");
                            } else {
                                arrayOffsetSnippet = "0";
                            }
                            currentBuilder.lineStart().invokeStatic(cache.jniUtil, "arrayCopy",
                                            jniEnvFieldName, parameterName, arrayOffsetSnippet, outArrayLocal(parameterName), "0", arrayLengthParameter).lineEnd(";");
                        } else {
                            currentBuilder.invokeStatic(cache.jniUtil, "createArray", jniEnvFieldName, parameterName).lineEnd(";");
                        }
                    } else {
                        CharSequence arrayLengthSnippet;
                        CharSequence arrayLengthParameter = getArrayLengthParameterName(out);
                        if (arrayLengthParameter != null) {
                            arrayLengthSnippet = arrayLengthParameter;
                            CharSequence arrayOffsetParameter = getArrayOffsetParameterName(out);
                            if (arrayOffsetParameter != null) {
                                parameterValueOverride.put(arrayOffsetParameter.toString(), "0");
                            }
                        } else {
                            arrayLengthSnippet = new CodeBuilder(currentBuilder).invokeStatic(cache.jniUtil, "GetArrayLength", jniEnvFieldName, parameterName).build();
                        }
                        currentBuilder.newArray(((ArrayType) parameterType).getComponentType(), arrayLengthSnippet).lineEnd(";");
                    }
                    parameterValueOverride.put(parameterName.toString(), outArrayLocal(parameterName));
                }
                return hasDirectionModifiers;
            }

            @Override
            void postUnmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence jniEnvFieldName, CharSequence resultVariableName) {
                if (isArrayWithDirectionModifiers(parameterType)) {
                    AnnotationMirror out = findOut(marshallerData.annotations);
                    if (out != null) {
                        CharSequence arrayLengthSnippet;
                        boolean trimToResult = trimToResult(out);
                        CharSequence arrayLengthParameter = getArrayLengthParameterName(out);
                        if (trimToResult) {
                            arrayLengthSnippet = resultVariableName;
                        } else if (arrayLengthParameter != null) {
                            arrayLengthSnippet = arrayLengthParameter;
                        } else {
                            arrayLengthSnippet = new CodeBuilder(currentBuilder).memberSelect(outArrayLocal(parameterName), "length", false).build();
                        }
                        CharSequence arrayOffsetSnippet;
                        CharSequence arrayOffsetParameter = getArrayOffsetParameterName(out);
                        if (arrayOffsetParameter != null) {
                            arrayOffsetSnippet = arrayOffsetParameter;
                        } else {
                            arrayOffsetSnippet = "0";
                        }
                        CodeBuilder copySnippet = new CodeBuilder(currentBuilder).invokeStatic(cache.jniUtil, "arrayCopy",
                                        jniEnvFieldName, outArrayLocal(parameterName), "0", parameterName, arrayOffsetSnippet, arrayLengthSnippet);
                        if (trimToResult) {
                            currentBuilder.lineStart("if (").write(resultVariableName).lineEnd(" > 0) {");
                            currentBuilder.indent();
                            currentBuilder.lineStart(copySnippet.build()).lineEnd(";");
                            currentBuilder.dedent();
                            currentBuilder.line("}");
                        } else {
                            currentBuilder.lineStart(copySnippet.build()).lineEnd(";");
                        }
                    }
                }
            }
        }

        private static final class ReferenceSnippets extends HotSpotToNativeMarshallerSnippets {

            private final DefinitionData data;

            ReferenceSnippets(NativeBridgeProcessor processor, DefinitionData data, MarshallerData marshallerData, Types types, TypeCache cache) {
                super(processor, marshallerData, types, cache);
                this.data = data;
            }

            @Override
            TypeMirror getEndPointMethodParameterType(TypeMirror type) {
                if (marshallerData.sameDirection) {
                    TypeMirror longType = types.getPrimitiveType(TypeKind.LONG);
                    return type.getKind() == TypeKind.ARRAY ? types.getArrayType(longType) : longType;
                } else {
                    return type;
                }
            }

            @Override
            Set<CharSequence> getEndPointSuppressedWarnings(CodeBuilder currentBuilder, TypeMirror type) {
                if (isParameterizedType(type)) {
                    return Collections.singleton("unchecked");
                } else {
                    return Collections.emptySet();
                }
            }

            @Override
            boolean preMarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence marshalledParametersOutput, CharSequence jniEnvFieldName,
                            Map<String, CharSequence> parameterValueOverrides) {
                if (marshallerData.sameDirection && parameterType.getKind() == TypeKind.ARRAY) {
                    AnnotationMirror in = findIn(marshallerData.annotations);
                    AnnotationMirror out = findOut(marshallerData.annotations);
                    CharSequence arrayLength = null;
                    CharSequence arrayOffsetParameter = null;
                    if (in != null) {
                        arrayLength = getArrayLengthParameterName(in);
                        arrayOffsetParameter = getArrayOffsetParameterName(in);
                    } else if (out != null) {
                        arrayLength = getArrayLengthParameterName(out);
                        arrayOffsetParameter = getArrayOffsetParameterName(out);
                    }
                    if (arrayLength == null) {
                        arrayLength = new CodeBuilder(currentBuilder).memberSelect(parameterName, "length", false).build();
                    }
                    currentBuilder.lineStart("if(").write(parameterName).write(" != null) ").lineEnd("{");
                    currentBuilder.indent();
                    currentBuilder.lineStart().invoke(marshalledParametersOutput, "writeInt", arrayLength).lineEnd(";");
                    CharSequence indexVariable = parameterName + "Index";
                    currentBuilder.lineStart().forLoop(List.of(new CodeBuilder(currentBuilder).write(types.getPrimitiveType(TypeKind.INT)).space().write(indexVariable).write(" = 0").build()),
                                    new CodeBuilder(currentBuilder).write(indexVariable).write(" < ").write(arrayLength).build(),
                                    List.of(new CodeBuilder(currentBuilder).write(indexVariable).write("++").build())).lineEnd(" {");
                    currentBuilder.indent();
                    TypeMirror componentType = ((ArrayType) parameterType).getComponentType();
                    CharSequence pos = arrayOffsetParameter != null ? new CodeBuilder(currentBuilder).write(arrayOffsetParameter).write(" + ").write(indexVariable).build() : indexVariable;
                    CharSequence parameterElement = new CodeBuilder(currentBuilder).arrayElement(parameterName, pos).build();
                    currentBuilder.lineStart().invoke(marshalledParametersOutput, "writeLong",
                                    marshallParameter(currentBuilder, componentType, parameterElement, marshalledParametersOutput, jniEnvFieldName)).lineEnd(";");
                    currentBuilder.dedent();
                    currentBuilder.line("}");
                    currentBuilder.dedent();
                    currentBuilder.line("} else {");
                    currentBuilder.indent();
                    currentBuilder.lineStart().invoke(marshalledParametersOutput, "writeInt", "-1").lineEnd(";");
                    currentBuilder.dedent();
                    currentBuilder.line("}");
                    return true;
                }
                return false;
            }

            @Override
            CharSequence marshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence formalParameter, CharSequence marshalledParametersOutput, CharSequence jniEnvFieldName) {
                if (marshallerData.sameDirection) {
                    if (parameterType.getKind() == TypeKind.ARRAY) {
                        // Already marshalled and written by preMarshallParameter. Return no code
                        // (null).
                        return null;
                    } else {
                        return marshallHotSpotToNativeProxyInHotSpot(currentBuilder, formalParameter);
                    }
                } else {
                    return formalParameter;
                }
            }

            @Override
            CharSequence preUnmarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence receiver, CharSequence jniEnvFieldName) {
                if (marshallerData.sameDirection) {
                    CharSequence resultVariable = "endPointResult";
                    currentBuilder.lineStart().write(getEndPointMethodParameterType(resultType)).space().write(resultVariable).write(" = ").write(invocationSnippet).lineEnd(";");
                    if (resultType.getKind() == TypeKind.ARRAY) {
                        TypeMirror componentType = ((ArrayType) resultType).getComponentType();
                        CharSequence hsResultVariable = "hsResult";
                        currentBuilder.lineStart().write(resultType).space().write(hsResultVariable).lineEnd(";");
                        currentBuilder.lineStart("if (").write(resultVariable).write(" != null) ").lineEnd("{");
                        currentBuilder.indent();
                        CharSequence arrayLength = new CodeBuilder(currentBuilder).memberSelect(resultVariable, "length", false).build();
                        currentBuilder.lineStart(hsResultVariable).write(" = ").newArray(componentType, arrayLength).lineEnd(";");
                        CharSequence hsResultIndexVariable = hsResultVariable + "Index";
                        currentBuilder.lineStart().forLoop(
                                        List.of(new CodeBuilder(currentBuilder).write(types.getPrimitiveType(TypeKind.INT)).space().write(hsResultIndexVariable).write(" = 0").build()),
                                        new CodeBuilder(currentBuilder).write(hsResultIndexVariable).write(" < ").write(arrayLength).build(),
                                        List.of(new CodeBuilder(currentBuilder).write(hsResultIndexVariable).write("++").build())).lineEnd(" {");
                        currentBuilder.indent();
                        CharSequence resultElement = new CodeBuilder(currentBuilder).arrayElement(resultVariable, hsResultIndexVariable).build();
                        currentBuilder.lineStart().arrayElement(hsResultVariable, hsResultIndexVariable).write(" = ").write(
                                        unmarshallResult(currentBuilder, componentType, resultElement, receiver, null, jniEnvFieldName)).lineEnd(";");
                        currentBuilder.dedent();
                        currentBuilder.line("}");
                        currentBuilder.dedent();
                        currentBuilder.line("} else {");
                        currentBuilder.indent();
                        currentBuilder.lineStart(hsResultVariable).write(" = null").lineEnd(";");
                        currentBuilder.dedent();
                        currentBuilder.line("}");
                        resultVariable = hsResultVariable;
                    }
                    return resultVariable;
                } else {
                    return null;
                }
            }

            @Override
            CharSequence unmarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence receiver, CharSequence marshalledResultInput,
                            CharSequence jniEnvFieldName) {
                if (marshallerData.sameDirection) {
                    if (resultType.getKind() == TypeKind.ARRAY) {
                        // Already unmarshalled by preUnmarshallResult. Just return the
                        // invocationSnippet.
                        return invocationSnippet;
                    } else {
                        CodeBuilder currentIsolateBuilder = new CodeBuilder(currentBuilder).invoke(receiver, "getIsolate");
                        return unmarshallHotSpotToNativeProxyInHotSpot(currentBuilder, invocationSnippet, currentIsolateBuilder.build());
                    }
                } else {
                    return unmarshallNativeToHotSpotProxyInHotSpot(currentBuilder, resultType, invocationSnippet, data);
                }
            }

            @Override
            CharSequence preMarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence jniEnvFieldName) {
                if (resultType.getKind() == TypeKind.ARRAY) {
                    CharSequence hsResultVariable = "hsResult";
                    TypeMirror hsResultType = jniTypeForJavaType(getEndPointMethodParameterType(resultType), types, cache);
                    currentBuilder.lineStart().write(hsResultType).space().write(hsResultVariable).lineEnd(";");
                    currentBuilder.lineStart("if (").write(invocationSnippet).write(" != null) ").lineEnd("{");
                    currentBuilder.indent();
                    TypeMirror componentType = ((ArrayType) resultType).getComponentType();
                    CharSequence hsArrayLength = new CodeBuilder(currentBuilder).memberSelect(invocationSnippet, "length", false).build();
                    if (marshallerData.sameDirection) {
                        CharSequence hsResultHandlesVariable = hsResultVariable + "Handles";
                        currentBuilder.lineStart().write(types.getArrayType(types.getPrimitiveType(TypeKind.LONG))).space().write(hsResultHandlesVariable).write(" = ").newArray(
                                        types.getPrimitiveType(TypeKind.LONG), hsArrayLength).lineEnd(";");
                        CharSequence indexVariable = hsResultHandlesVariable + "Index";
                        currentBuilder.lineStart().forLoop(List.of(new CodeBuilder(currentBuilder).write(types.getPrimitiveType(TypeKind.INT)).space().write(indexVariable).write(" = 0").build()),
                                        new CodeBuilder(currentBuilder).write(indexVariable).write(" < ").write(hsArrayLength).build(),
                                        List.of(new CodeBuilder(currentBuilder).write(indexVariable).write("++").build())).lineEnd(" {");
                        currentBuilder.indent();
                        CharSequence arrayElement = new CodeBuilder(currentBuilder).arrayElement(invocationSnippet, indexVariable).build();
                        CharSequence value = marshallResult(currentBuilder, componentType, arrayElement, null, jniEnvFieldName);
                        currentBuilder.lineStart().arrayElement(hsResultHandlesVariable, indexVariable).write(" = ").write(value).lineEnd(";");
                        currentBuilder.dedent();
                        currentBuilder.line("}");
                        currentBuilder.lineStart(hsResultVariable).write(" = ").invokeStatic(cache.jniUtil, "createHSArray", jniEnvFieldName, hsResultHandlesVariable).lineEnd(";");
                    } else {
                        CharSequence nullptr = new CodeBuilder(currentBuilder).invokeStatic(cache.wordFactory, "nullPointer").build();
                        CharSequence componentTypeCanonicalName = new CodeBuilder(currentBuilder).invoke(new CodeBuilder(currentBuilder).classLiteral(componentType).build(),
                                        "getCanonicalName").build();
                        CharSequence componentTypeBinaryName = new CodeBuilder(currentBuilder).invokeStatic(cache.jniUtil, "getBinaryName", componentTypeCanonicalName).build();
                        CharSequence hsArrayComponentType = new CodeBuilder(currentBuilder).invokeStatic(cache.jniUtil, "findClass", jniEnvFieldName, nullptr, componentTypeBinaryName, "true").build();
                        currentBuilder.lineStart(hsResultVariable).write(" = ").invokeStatic(cache.jniUtil, "NewObjectArray", jniEnvFieldName, hsArrayLength, hsArrayComponentType, nullptr).lineEnd(
                                        ";");
                        CharSequence indexVariable = hsResultVariable + "Index";
                        currentBuilder.lineStart().forLoop(List.of(new CodeBuilder(currentBuilder).write(types.getPrimitiveType(TypeKind.INT)).space().write(indexVariable).write(" = 0").build()),
                                        new CodeBuilder(currentBuilder).write(indexVariable).write(" < ").write(hsArrayLength).build(),
                                        List.of(new CodeBuilder(currentBuilder).write(indexVariable).write("++").build())).lineEnd(" {");
                        currentBuilder.indent();
                        CharSequence hsResultElementVariable = hsResultVariable + "Element";
                        CharSequence arrayElement = new CodeBuilder(currentBuilder).arrayElement(invocationSnippet, indexVariable).build();
                        CharSequence value = marshallResult(currentBuilder, componentType, arrayElement, null, jniEnvFieldName);
                        currentBuilder.lineStart().write(cache.jObject).space().write(hsResultElementVariable).write(" = ").write(value).lineEnd(";");
                        currentBuilder.lineStart().invokeStatic(cache.jniUtil, "SetObjectArrayElement", jniEnvFieldName, hsResultVariable, indexVariable, hsResultElementVariable).lineEnd(";");
                        currentBuilder.dedent();
                        currentBuilder.line("}");
                    }
                    currentBuilder.dedent();
                    currentBuilder.line("} else {");
                    currentBuilder.indent();
                    currentBuilder.lineStart(hsResultVariable).write(" = ").invokeStatic(cache.wordFactory, "nullPointer").lineEnd(";");
                    currentBuilder.dedent();
                    currentBuilder.line("}");
                    return hsResultVariable;
                }
                return null;
            }

            @Override
            CharSequence marshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence marshalledResultOutput, CharSequence jniEnvFieldName) {
                if (resultType.getKind() == TypeKind.ARRAY) {
                    // Already marshalled by preMarshallResult. Just return the invocationSnippet.
                    return invocationSnippet;
                } else {
                    if (marshallerData.sameDirection) {
                        return marshallHotSpotToNativeProxyInNative(currentBuilder, invocationSnippet);
                    } else {
                        return marshallNativeToHotSpotProxyInNative(currentBuilder, invocationSnippet);
                    }
                }
            }

            @Override
            boolean preUnmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence marshalledResultInput, CharSequence jniEnvFieldName,
                            Map<String, CharSequence> parameterValueOverride) {
                if (parameterType.getKind() == TypeKind.ARRAY) {
                    TypeMirror componentType = ((ArrayType) parameterType).getComponentType();
                    AnnotationMirror in = findIn(marshallerData.annotations);
                    AnnotationMirror out = findOut(marshallerData.annotations);
                    if (marshallerData.sameDirection) {
                        CharSequence arrayOffsetParameter = null;
                        if (in != null) {
                            arrayOffsetParameter = getArrayOffsetParameterName(in);
                        } else if (out != null) {
                            arrayOffsetParameter = getArrayOffsetParameterName(out);
                        }
                        if (arrayOffsetParameter != null) {
                            parameterValueOverride.put(arrayOffsetParameter.toString(), "0");
                        }
                        currentBuilder.lineStart().write(parameterType).space().write(parameterName).lineEnd(";");
                        CharSequence arrayLengthVariable = parameterName + "Length";
                        currentBuilder.lineStart().write(types.getPrimitiveType(TypeKind.INT)).space().write(arrayLengthVariable).write(" = ").invoke(marshalledResultInput, "readInt").lineEnd(";");
                        currentBuilder.lineStart("if(").write(arrayLengthVariable).write(" != -1) ").lineEnd("{");
                        currentBuilder.indent();
                        currentBuilder.lineStart(parameterName).write(" = ").newArray(componentType, arrayLengthVariable).lineEnd(";");
                        CharSequence arrayIndexVariable = parameterName + "Index";
                        currentBuilder.lineStart().forLoop(List.of(new CodeBuilder(currentBuilder).write(types.getPrimitiveType(TypeKind.INT)).space().write(arrayIndexVariable).write(" = 0").build()),
                                        new CodeBuilder(currentBuilder).write(arrayIndexVariable).write(" < ").write(arrayLengthVariable).build(),
                                        List.of(new CodeBuilder(currentBuilder).write(arrayIndexVariable).write("++").build())).lineEnd(" {");
                        currentBuilder.indent();
                        CharSequence sinkArrayElement = new CodeBuilder(currentBuilder).arrayElement(parameterName, arrayIndexVariable).build();
                        CharSequence handle = new CodeBuilder(currentBuilder).invoke(marshalledResultInput, "readLong").build();
                        currentBuilder.lineStart(sinkArrayElement).write(" = ").write(unmarshallParameter(currentBuilder, componentType, handle, marshalledResultInput, jniEnvFieldName)).lineEnd(";");
                        currentBuilder.dedent();
                        currentBuilder.line("}");
                        currentBuilder.dedent();
                        currentBuilder.line("} else {");
                        currentBuilder.indent();
                        currentBuilder.lineStart(parameterName).write(" = null").lineEnd(";");
                        currentBuilder.dedent();
                        currentBuilder.line("}");
                    } else {
                        CharSequence arrayLengthParameter = null;
                        CharSequence arrayOffsetParameter = null;
                        if (in != null) {
                            arrayLengthParameter = getArrayLengthParameterName(in);
                            arrayOffsetParameter = getArrayOffsetParameterName(in);
                        } else if (out != null) {
                            arrayLengthParameter = getArrayLengthParameterName(out);
                            arrayOffsetParameter = getArrayOffsetParameterName(out);
                        }
                        if (arrayLengthParameter == null) {
                            arrayLengthParameter = new CodeBuilder(currentBuilder).invokeStatic(cache.jniUtil, "GetArrayLength", jniEnvFieldName, parameterName).build();
                        }
                        if (arrayOffsetParameter != null) {
                            parameterValueOverride.put(arrayOffsetParameter.toString(), "0");
                        }
                        CharSequence arrayVariable = outArrayLocal(parameterName);
                        String arrayIndexVariable = outArrayLocal(parameterName) + "Index";
                        parameterValueOverride.put(parameterName.toString(), arrayVariable);

                        currentBuilder.lineStart().write(parameterType).space().write(arrayVariable).lineEnd(";");
                        currentBuilder.lineStart().write("if (").invoke(parameterName, "isNonNull").lineEnd("){");
                        currentBuilder.indent();
                        currentBuilder.lineStart(arrayVariable).write(" = ").newArray(componentType, arrayLengthParameter).lineEnd(";");
                        if (out == null || in != null) {
                            // Default direction modifier (`in`) or explicit `in` modifier.
                            currentBuilder.lineStart().forLoop(
                                            List.of(new CodeBuilder(currentBuilder).write(types.getPrimitiveType(TypeKind.INT)).space().write(arrayIndexVariable).write(" = 0").build()),
                                            new CodeBuilder(currentBuilder).write(arrayIndexVariable).write(" < ").write(arrayVariable).write(".length").build(),
                                            List.of(new CodeBuilder(currentBuilder).write(arrayIndexVariable).write("++").build())).lineEnd(" {");
                            currentBuilder.indent();
                            String arrayElementVariable = arrayVariable + "Element";
                            CharSequence index = arrayOffsetParameter == null ? arrayIndexVariable
                                            : new CodeBuilder(currentBuilder).write(arrayOffsetParameter).write(" + ").write(arrayIndexVariable).build();
                            currentBuilder.lineStart().write(cache.jObject).space().write(arrayElementVariable).write(" = ").invokeStatic(cache.jniUtil, "GetObjectArrayElement", jniEnvFieldName,
                                            parameterName, index).lineEnd(";");
                            currentBuilder.lineStart().arrayElement(arrayVariable, arrayIndexVariable).write(" = ").write(
                                            unmarshallParameter(currentBuilder, componentType, arrayElementVariable, null, jniEnvFieldName)).lineEnd(";");
                            currentBuilder.dedent();
                            currentBuilder.line("}");
                        }
                        currentBuilder.dedent();
                        currentBuilder.line("} else {");
                        currentBuilder.indent();
                        currentBuilder.lineStart(arrayVariable).lineEnd(" = null;");
                        currentBuilder.dedent();
                        currentBuilder.line("}");
                    }
                    return true;
                }
                return false;
            }

            @Override
            void postUnmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence jniEnvFieldName, CharSequence resultVariableName) {
                if (parameterType.getKind() == TypeKind.ARRAY) {
                    AnnotationMirror out = findOut(marshallerData.annotations);
                    if (out != null) {
                        TypeMirror componentType = ((ArrayType) parameterType).getComponentType();
                        CharSequence arrayVariable = outArrayLocal(parameterName);
                        CharSequence arrayLengthSnippet;
                        boolean trimToResult = trimToResult(out);
                        CharSequence arrayLengthParameter = getArrayLengthParameterName(out);
                        if (trimToResult) {
                            arrayLengthSnippet = resultVariableName;
                        } else if (arrayLengthParameter != null) {
                            arrayLengthSnippet = arrayLengthParameter;
                        } else {
                            arrayLengthSnippet = new CodeBuilder(currentBuilder).memberSelect(arrayVariable, "length", false).build();
                        }
                        CharSequence arrayOffsetParameter = getArrayOffsetParameterName(out);
                        CharSequence arrayNullCheck = new CodeBuilder(currentBuilder).write(arrayVariable).write(" != null").build();
                        if (trimToResult) {
                            currentBuilder.lineStart("if (").write(arrayNullCheck).write(" && ").write(resultVariableName).lineEnd(" > 0) {");
                        } else {
                            currentBuilder.lineStart("if (").write(arrayNullCheck).lineEnd(") {");
                        }
                        currentBuilder.indent();
                        String arrayIndexVariable = outArrayLocal(parameterName) + "Index";
                        currentBuilder.lineStart().forLoop(List.of(new CodeBuilder(currentBuilder).write(types.getPrimitiveType(TypeKind.INT)).space().write(arrayIndexVariable).write(" = 0").build()),
                                        new CodeBuilder(currentBuilder).write(arrayIndexVariable).write(" < ").write(arrayLengthSnippet).build(),
                                        List.of(new CodeBuilder(currentBuilder).write(arrayIndexVariable).write("++").build())).lineEnd(" {");
                        currentBuilder.indent();
                        String arrayElementVariable = arrayVariable + "Element";
                        currentBuilder.lineStart().write(componentType).space().write(arrayElementVariable).write(" = ").arrayElement(arrayVariable, arrayIndexVariable).lineEnd(";");
                        CharSequence index = arrayOffsetParameter == null ? arrayIndexVariable
                                        : new CodeBuilder(currentBuilder).write(arrayOffsetParameter).write(" + ").write(arrayIndexVariable).build();
                        CharSequence value = marshallResult(currentBuilder, componentType, arrayElementVariable, null, jniEnvFieldName);
                        currentBuilder.lineStart().invokeStatic(cache.jniUtil, "SetObjectArrayElement", jniEnvFieldName, parameterName, index, value).lineEnd(";");
                        currentBuilder.dedent();
                        currentBuilder.line("}");
                        currentBuilder.dedent();
                        currentBuilder.line("}");
                    }
                }
            }

            @Override
            CharSequence unmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence marshalledParametersInput, CharSequence jniEnvFieldName) {
                if (parameterType.getKind() == TypeKind.ARRAY) {
                    // Already marshalled by preUnmarshallParameter. Return parameterName;
                    return parameterName;
                } else {
                    if (marshallerData.sameDirection) {
                        return unmarshallHotSpotToNativeProxyInNative(currentBuilder, parameterType, parameterName, data);
                    } else {
                        return unmarshallNativeToHotSpotProxyInNative(currentBuilder, parameterName, jniEnvFieldName);
                    }
                }
            }
        }

        private static final class RawReferenceSnippets extends HotSpotToNativeMarshallerSnippets {

            RawReferenceSnippets(NativeBridgeProcessor processor, MarshallerData marshallerData, Types types, TypeCache cache) {
                super(processor, marshallerData, types, cache);
            }

            @Override
            TypeMirror getEndPointMethodParameterType(TypeMirror type) {
                return cache.object;
            }

            @Override
            CharSequence marshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence formalParameter, CharSequence marshalledParametersOutput, CharSequence jniEnvFieldName) {
                return formalParameter;
            }

            @Override
            CharSequence unmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence marshalledParametersInput, CharSequence jniEnvFieldName) {
                return new CodeBuilder(currentBuilder).invoke(parameterName, "rawValue").build();
            }

            @Override
            CharSequence marshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence marshalledResultOutput, CharSequence jniEnvFieldName) {
                CharSequence value = new CodeBuilder(currentBuilder).cast(types.getPrimitiveType(TypeKind.LONG), invocationSnippet).build();
                return new CodeBuilder(currentBuilder).invokeStatic(cache.wordFactory, "pointer", value).build();
            }

            @Override
            CharSequence unmarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence receiver, CharSequence marshalledResultInput,
                            CharSequence jniEnvFieldName) {
                return invocationSnippet;
            }
        }

        private static final class CustomSnippets extends HotSpotToNativeMarshallerSnippets {

            CustomSnippets(NativeBridgeProcessor processor, MarshallerData marshallerData, Types types, TypeCache cache) {
                super(processor, marshallerData, types, cache);
            }

            @Override
            TypeMirror getEndPointMethodParameterType(TypeMirror type) {
                return types.getArrayType(types.getPrimitiveType(TypeKind.BYTE));
            }

            @Override
            CharSequence marshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence formalParameter, CharSequence marshalledParametersOutput, CharSequence jniEnvFieldName) {
                return new CodeBuilder(currentBuilder).invoke(marshallerData.name, "write", marshalledParametersOutput, formalParameter).build();
            }

            @Override
            CharSequence unmarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence receiver, CharSequence marshalledResultInput,
                            CharSequence jniEnvFieldName) {
                CodeBuilder binaryInput = new CodeBuilder(currentBuilder).invokeStatic(cache.binaryInput, "create", invocationSnippet);
                return new CodeBuilder(currentBuilder).invoke(marshallerData.name, "read", binaryInput.build()).build();
            }

            @Override
            CharSequence preUnmarshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence receiver, CharSequence jniEnvFieldName) {
                CharSequence resultVariable = "endPointResult";
                currentBuilder.lineStart().write(types.getArrayType(types.getPrimitiveType(TypeKind.BYTE))).space().write(resultVariable).write(" = ").write(invocationSnippet).lineEnd(";");
                return resultVariable;
            }

            @Override
            CharSequence marshallResult(CodeBuilder currentBuilder, TypeMirror resultType, CharSequence invocationSnippet, CharSequence marshalledResultOutput, CharSequence jniEnvFieldName) {
                return new CodeBuilder(currentBuilder).invoke(marshallerData.name, "write", marshalledResultOutput, invocationSnippet).build();
            }

            @Override
            boolean preUnmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence marshalledParametersInput, CharSequence jniEnvFieldName,
                            Map<String, CharSequence> parameterValueOverride) {
                currentBuilder.lineStart().write(parameterType).space().write(parameterName).write(" = ").invoke(marshallerData.name, "read", marshalledParametersInput).lineEnd(";");
                return true;
            }

            @Override
            CharSequence unmarshallParameter(CodeBuilder currentBuilder, TypeMirror parameterType, CharSequence parameterName, CharSequence marshalledParametersInput, CharSequence jniEnvFieldName) {
                return parameterName;
            }
        }
    }
}
