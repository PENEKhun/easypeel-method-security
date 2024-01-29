/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.epsec.core;

import static javax.tools.Diagnostic.Kind.ERROR;
import static org.epsec.core.FullyQualifiedClassName.ASPECT;
import static org.epsec.core.FullyQualifiedClassName.BEFORE;
import static org.epsec.core.FullyQualifiedClassName.COMPONENT;
import static org.epsec.core.FullyQualifiedClassName.ENABLE_ASPECT_JAUTO_PROXY;
import static org.epsec.core.FullyQualifiedClassName.HTTP_SERVLET_REQUEST;
import static org.epsec.core.FullyQualifiedClassName.JOIN_POINT;
import static org.epsec.core.FullyQualifiedClassName.REQUEST_CONTEXT_HOLDER;
import static org.epsec.core.FullyQualifiedClassName.SERVLET_REQUEST_ATTRIBUTES;
import static org.epsec.core.FullyQualifiedClassName.isWebAnnotation;

import java.io.IOException;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

import org.epsec.engine.MethodBanManager;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

/**
 * MethodBan Annotation Processor.
 *
 * @author PENEKhun
 */
public class MethodBanProcessor extends AbstractProcessor {

  private boolean isAlreadyProcessed;

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Set.of(MethodBan.class.getName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    final Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(MethodBan.class);
    for (Element element : elements) {
      if (isAlreadyProcessed) {
        break;
      }
      checkValidMethodBan(element);
      processMethodBan(element);
      isAlreadyProcessed = true;
    }
    return true;
  }

  private void processMethodBan(Element element) {
    generateEnableAopClass(element);
    generateMethodBanAspect(element);
  }

  private void generateMethodBanAspect(Element element) {
    final ClassName before = ClassName.bestGuess(BEFORE.getName());
    final AnnotationSpec annotationSpec = AnnotationSpec.builder(before)
        .addMember("value", "$S", "@annotation(%s)" .formatted(ClassName.get(MethodBan.class)))
        .build();

    final MethodSpec getUserIpMethodSpec = MethodSpec.methodBuilder("getUserIp" + System.nanoTime())
        .addModifiers(Modifier.PRIVATE)
        .returns(String.class)
        .addCode(CodeBlock.builder()
            .addStatement("$T request = (($T) $T.getRequestAttributes()).getRequest()",
                ClassName.bestGuess(HTTP_SERVLET_REQUEST.getName()),
                ClassName.bestGuess(SERVLET_REQUEST_ATTRIBUTES.getName()),
                ClassName.bestGuess(REQUEST_CONTEXT_HOLDER.getName()))
            .addStatement("$T xForwardedForHeader = $L.getHeader($S)", String.class, "request",
                "X-Forwarded-For")
            .beginControlFlow("if ($L == null)", "xForwardedForHeader")
            .addStatement("return $L.getRemoteAddr()", "request")
            .endControlFlow()
            .addStatement("return $L.split($S)[0]", "xForwardedForHeader", ",")
            .build())
        .build();

    final MethodBan methodBan = element.getAnnotation(MethodBan.class);
    final CodeBlock codes = CodeBlock.builder()
        .addStatement("$T packageName = $L.getSignature().getDeclaringTypeName()", String.class, "joinPoint")
        .addStatement("$T methodName = $L.getSignature().getName()", String.class, "joinPoint")
        .addStatement("$T cache = new $T(packageName + methodName, $L, $L, $L, $S)",
            MethodBanManager.class, MethodBanManager.class, methodBan.times(), methodBan.seconds(),
            methodBan.banSeconds(), methodBan.banMessage())
        .addStatement("$L.checkBanAndAccess($L())", "cache", getUserIpMethodSpec.name)
        .build();

    final MethodSpec methodSpec = MethodSpec.methodBuilder("beforeMethodBan" + System.nanoTime())
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(annotationSpec)
        .addParameter(ClassName.bestGuess(JOIN_POINT.getName()), "joinPoint")
        .addCode(codes)
        .build();

    final TypeSpec classSpec = TypeSpec.classBuilder("MethodBanAspect" + System.nanoTime())
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(ClassName.bestGuess(ASPECT.getName()))
        .addAnnotation(ClassName.bestGuess(COMPONENT.getName()))
        .addMethod(methodSpec)
        .addMethod(getUserIpMethodSpec)
        .build();

    final String fullPackageName = element.getEnclosingElement().toString();
    final String originalPackageName = fullPackageName.substring(0, fullPackageName.lastIndexOf("."));
    saveJavaFile(originalPackageName, classSpec);
  }

  private void generateEnableAopClass(Element element) {
    final TypeSpec enableAopClass = TypeSpec.classBuilder("EnableAopClass" + System.nanoTime())
        .addModifiers(Modifier.PUBLIC)
        .addAnnotation(ClassName.bestGuess(COMPONENT.getName()))
        .addAnnotation(ClassName.bestGuess(ENABLE_ASPECT_JAUTO_PROXY.getName()))
        .build();

    final String packageName = element.getEnclosingElement().toString();
    saveJavaFile(packageName, enableAopClass);
  }

  private void saveJavaFile(String fullPackageName, TypeSpec classSpec) {
    final Filer filer = processingEnv.getFiler();
    final String originalPackageName = fullPackageName.substring(0, fullPackageName.lastIndexOf("."));
    try {
      JavaFile.builder(originalPackageName, classSpec)
          .build()
          .writeTo(filer);
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(ERROR, "Fatal error" + e.getMessage());
    }
  }

  private void checkValidMethodBan(Element element) {
    final MethodBan methodBan = element.getAnnotation(MethodBan.class);
    if (methodBan.times() <= 1) {
      processingEnv.getMessager().printMessage(ERROR, "times must be greater than 1", element);
    }

    if (methodBan.seconds() <= 0) {
      processingEnv.getMessager().printMessage(ERROR, "seconds must be greater than 0", element);
    }

    if (methodBan.banSeconds() <= 0) {
      processingEnv.getMessager().printMessage(ERROR, "banSeconds must be greater than 0", element);
    }

    if (!element.getModifiers().contains(Modifier.PUBLIC)) {
      processingEnv.getMessager().printMessage(ERROR, "Method must be public", element);
    }

    if (!isUsedWithMappingAnnotation(element)) {
      processingEnv.getMessager()
          .printMessage(ERROR, "@MethodBan must be used with a Spring Mapping Annotation " +
              "(@GetMapping, @PostMapping, @PutMapping, @DeleteMapping, @PatchMapping)", element);
    }
  }

  private boolean isUsedWithMappingAnnotation(Element element) {
    boolean isUsedWithMappingAnnotaion = false;
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      if (isWebAnnotation(mirror.getAnnotationType().toString())) {
        isUsedWithMappingAnnotaion = true;
        break;
      }
    }
    return isUsedWithMappingAnnotaion;
  }
}