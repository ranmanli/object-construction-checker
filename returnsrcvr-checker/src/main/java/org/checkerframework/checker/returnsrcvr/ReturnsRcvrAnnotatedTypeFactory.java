package org.checkerframework.checker.returnsrcvr;

import java.util.ArrayList;
import java.util.Collection;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.checker.framework.AutoValueSupport;
import org.checkerframework.checker.framework.FrameworkSupport;
import org.checkerframework.checker.framework.LombokSupport;
import org.checkerframework.checker.returnsrcvr.qual.MaybeThis;
import org.checkerframework.checker.returnsrcvr.qual.This;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.typeannotator.ListTypeAnnotator;
import org.checkerframework.framework.type.typeannotator.TypeAnnotator;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;

public class ReturnsRcvrAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

  AnnotationMirror THIS_ANNOT;
  // the collection of the built-in framework supports for returns receiver checker
  Collection<FrameworkSupport> frameworkSupports;

  public ReturnsRcvrAnnotatedTypeFactory(BaseTypeChecker checker) {
    super(checker);
    THIS_ANNOT = AnnotationBuilder.fromClass(elements, This.class);

    boolean disableAutoValue = false;
    boolean disableLombok = false;

    String disabledFrameworkSupports =
        checker.getOption(ReturnsRcvrChecker.DISABLED_FRAMEWORK_SUPPORTS);
    if (disabledFrameworkSupports != null) {
      for (String disabledFrameworkSupport : disabledFrameworkSupports.split(",")) {
        if (disabledFrameworkSupport.equals(ReturnsRcvrChecker.AUTOVALUE_SUPPORT)) {
          disableAutoValue = true;
        }
        if (disabledFrameworkSupport.equals(ReturnsRcvrChecker.LOMBOK_SUPPORT)) {
          disableLombok = true;
        }
      }
    }

    frameworkSupports = new ArrayList<FrameworkSupport>();
    if (!disableAutoValue) {
      frameworkSupports.add(new AutoValueSupport());
    }
    if (!disableLombok) {
      frameworkSupports.add(new LombokSupport());
    }
    // we have to call this explicitly
    this.postInit();
  }

  @Override
  protected TypeAnnotator createTypeAnnotator() {
    return new ListTypeAnnotator(super.createTypeAnnotator(), new ReturnsRcvrTypeAnnotator(this));
  }

  private class ReturnsRcvrTypeAnnotator extends TypeAnnotator {

    public ReturnsRcvrTypeAnnotator(AnnotatedTypeFactory typeFactory) {
      super(typeFactory);
    }

    @Override
    public Void visitExecutable(AnnotatedTypeMirror.AnnotatedExecutableType t, Void p) {

      AnnotatedTypeMirror returnType = t.getReturnType();
      AnnotationMirror maybeThisAnnot = AnnotationBuilder.fromClass(elements, MaybeThis.class);
      AnnotationMirror retAnnotation = returnType.getAnnotationInHierarchy(maybeThisAnnot);
      if (retAnnotation != null && AnnotationUtils.areSame(retAnnotation, THIS_ANNOT)) {
        // add @This to the receiver type
        AnnotatedTypeMirror.AnnotatedDeclaredType receiverType = t.getReceiverType();
        receiverType.replaceAnnotation(THIS_ANNOT);
      }

      // skip constructors
      if (!isConstructor(t)) {
        // check each supported framework
        for (FrameworkSupport frameworkSupport : frameworkSupports) {
          // see if the method in the framework should return this
          if (frameworkSupport.knownToReturnThis(t)) {
            // add @This annotation
            returnType.replaceAnnotation(THIS_ANNOT);
            AnnotatedTypeMirror.AnnotatedDeclaredType receiverType = t.getReceiverType();
            receiverType.replaceAnnotation(THIS_ANNOT);
            break;
          }
        }
      }

      return super.visitExecutable(t, p);
    }
  }

  private boolean isConstructor(AnnotatedTypeMirror.AnnotatedExecutableType t) {
    ExecutableElement element = t.getElement();
    return element.getKind().equals(ElementKind.CONSTRUCTOR);
  }
}
