package org.checkerframework.checker.objectconstruction.framework;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.sun.source.tree.NewClassTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.objectconstruction.ObjectConstructionAnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;

/**
 * AutoValue Support for object construction checker, add CalledMethods annotation to 
 * the code generated by AutoValue 
 *
 */
public class AutoValueSupport implements FrameworkSupport {

  private ObjectConstructionAnnotatedTypeFactory atypeFactory;

  public AutoValueSupport(ObjectConstructionAnnotatedTypeFactory atypeFactory) {
    this.atypeFactory = atypeFactory;
  }

  /**
   * add CalledMethods annotation to a constructor call inside a generated 
   * toBuilder implementation in AutoValue
   * 
   * @param tree
   * @param type
   */
  @Override
  public void handleConstructor(NewClassTree tree, AnnotatedTypeMirror type) {
    ExecutableElement element = TreeUtils.elementFromUse(tree);
    TypeMirror superclass = ((TypeElement) element.getEnclosingElement()).getSuperclass();

    if (!superclass.getKind().equals(TypeKind.NONE)
        && FrameworkSupportUtils.hasAnnotation(
            TypesUtils.getTypeElement(superclass), AutoValue.Builder.class)
        && element.getParameters().size() > 0) {
      handleToBuilderType(
          type, superclass, TypesUtils.getTypeElement(superclass).getEnclosingElement());
    }
  }

  /**
   * determine the required properties and add a corresponding @CalledMethods annotation to the
   * receiver 
   *
   * @param t
   */
  @Override
  public void handlePossibleBuilderBuildMethod (AnnotatedExecutableType t) {

    ExecutableElement element = t.getElement();

    TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
    Element nextEnclosingElement = enclosingElement.getEnclosingElement();

    if (FrameworkSupportUtils.hasAnnotation(enclosingElement, AutoValue.Builder.class)) {
      assert FrameworkSupportUtils.hasAnnotation(nextEnclosingElement, AutoValue.class)
          : "class " + nextEnclosingElement.getSimpleName() + " is missing @AutoValue annotation";
      if (isBuilderBuildMethod(element, nextEnclosingElement)) {
        // determine the required properties and add a corresponding @CalledMethods annotation
        Set<String> avBuilderSetterNames = getAutoValueBuilderSetterMethodNames(enclosingElement);
        List<String> requiredProperties =
            getAutoValueRequiredProperties(nextEnclosingElement, avBuilderSetterNames);
        AnnotationMirror newCalledMethodsAnno =
            createCalledMethodsForAutoValueProperties(requiredProperties, avBuilderSetterNames);
        t.getReceiverType().addAnnotation(newCalledMethodsAnno);
      }
    }
  }

  /**
   * For a toBuilder routine, we know that the returned Builder effectively has had all the required
   * setters invoked. Add a CalledMethods annotation capturing this fact.
   *
   * @param t
   */
  @Override
  public void handleToBuilder(AnnotatedExecutableType t) {

    AnnotatedTypeMirror returnType = t.getReturnType();
    ExecutableElement element = t.getElement();
    
    String methodName = element.getSimpleName().toString();
    
    //make sure the method is toBuilder
    if ("toBuilder".equals(methodName)) {

		TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
		TypeMirror superclass = enclosingElement.getSuperclass();
		
		if (FrameworkSupportUtils.hasAnnotation(enclosingElement, AutoValue.class)
		    && element.getModifiers().contains(Modifier.ABSTRACT)) {
		  handleToBuilderType(returnType, returnType.getUnderlyingType(), enclosingElement);
		}
		
	   if (!superclass.getKind().equals(TypeKind.NONE)) {
		  TypeElement superElement = TypesUtils.getTypeElement(superclass);
		  if (FrameworkSupportUtils.hasAnnotation(superElement, AutoValue.class)) {
		    handleToBuilderType(returnType, returnType.getUnderlyingType(), superElement);
		  }
       }
    }
    
  }

  /**
   * is element the build method for AutoValue Builder?
   *
   * @param element
   * @param nextEnclosingElement
   * @return {@code true} if return type of element is enclosing AutoValue class and method is
   *     abstract, {@code false} otherwise
   */
  private boolean isBuilderBuildMethod(ExecutableElement element, Element nextEnclosingElement) {
    return element.getModifiers().contains(Modifier.ABSTRACT)
        && TypesUtils.getTypeElement(element.getReturnType()).equals(nextEnclosingElement);
  }

  /**
   * Update a particular type associated with a toBuilder with the relevant CalledMethods
   * annotation. This can be the return type of toBuilder or the corresponding generated "copy"
   * constructor
   *
   * @param type type to update
   * @param builderType type of abstract @AutoValue.Builder class
   * @param classElement corresponding AutoValue class
   */
  private void handleToBuilderType(
      AnnotatedTypeMirror type, TypeMirror builderType, Element classElement) {
    Element builderElement = TypesUtils.getTypeElement(builderType);
    Set<String> avBuilderSetterNames = getAutoValueBuilderSetterMethodNames(builderElement);
    List<String> requiredProperties =
        getAutoValueRequiredProperties(classElement, avBuilderSetterNames);
    AnnotationMirror calledMethodsAnno =
        createCalledMethodsForAutoValueProperties(requiredProperties, avBuilderSetterNames);
    type.replaceAnnotation(calledMethodsAnno);
  }

  /**
   * creates a @CalledMethods annotation for the given property names, converting the names to the
   * corresponding setter method name in the Builder
   *
   * @param propertyNames
   * @param avBuilderSetterNames
   * @return
   */
  private AnnotationMirror createCalledMethodsForAutoValueProperties(
      final List<String> propertyNames, Set<String> avBuilderSetterNames) {
    String[] calledMethodNames =
        propertyNames.stream()
            .map(prop -> autoValuePropToBuilderSetterName(prop, avBuilderSetterNames))
            .toArray(String[]::new);
    return atypeFactory.createCalledMethods(calledMethodNames);
  }

  private static String autoValuePropToBuilderSetterName(
      String prop, Set<String> builderSetterNames) {
    // we have two cases, depending on whether AutoValue strips JavaBean-style prefixes 'get' and
    // 'is'
    Set<String> possiblePropNames = new LinkedHashSet<>();
    possiblePropNames.add(prop);
    if (prop.startsWith("get") && prop.length() > 3 && Character.isUpperCase(prop.charAt(3))) {
      possiblePropNames.add(Introspector.decapitalize(prop.substring(3)));
    } else if (prop.startsWith("is")
        && prop.length() > 2
        && Character.isUpperCase(prop.charAt(2))) {
      possiblePropNames.add(Introspector.decapitalize(prop.substring(2)));
    }

    for (String propName : possiblePropNames) {
      // in each case, the setter may be the property name itself, or prefixed by 'set'
      ImmutableSet<String> setterNamesToTry =
          ImmutableSet.of(propName, "set" + FrameworkSupportUtils.capitalize(propName));
      for (String setterName : setterNamesToTry) {
        if (builderSetterNames.contains(setterName)) {
          return setterName;
        }
      }
    }

    // nothing worked
    throw new RuntimeException(
        "could not find Builder setter name for property "
            + prop
            + " all names "
            + builderSetterNames);
  }

  /**
   * computes the required properties of an @AutoValue class
   *
   * @param autoValueClassElement the @AutoValue class
   * @param avBuilderSetterNames
   * @return a list of required property names
   */
  private List<String> getAutoValueRequiredProperties(
      final Element autoValueClassElement, Set<String> avBuilderSetterNames) {
    return getAllAbstractMethods(autoValueClassElement).stream()
        .filter(member -> isAutoValueRequiredProperty(member, avBuilderSetterNames))
        .map(e -> e.getSimpleName().toString())
        .collect(Collectors.toList());
  }

  /**
   * Does member represent a required property of an AutoValue class?
   *
   * @param member member of an AutoValue class or superclass
   * @param allBuilderMethodNames names of methods in corresponding AutoValue builder
   * @return {@code true} if member is required, {@code false} otherwise
   */
  private boolean isAutoValueRequiredProperty(Element member, Set<String> allBuilderMethodNames) {
    String name = member.getSimpleName().toString();
    if (IGNORED_METHOD_NAMES.contains(name)) {
      return false;
    }
    TypeMirror returnType = ((ExecutableElement) member).getReturnType();
    if (returnType.getKind().equals(TypeKind.VOID)) {
      return false;
    }
    // shouldn't have a nullable return
    boolean hasNullable =
        Stream.concat(
                //	        		elements.getAllannotationMirrors()
                atypeFactory.getElementUtils().getAllAnnotationMirrors(member).stream(),
                returnType.getAnnotationMirrors().stream())
            .anyMatch(anm -> AnnotationUtils.annotationName(anm).endsWith(".Nullable"));
    if (hasNullable) {
      return false;
    }
    // if return type of foo() is a Guava Immutable type, not required if there is a
    // builder method fooBuilder()
    if (FrameworkSupportUtils.isGuavaImmutableType(returnType)
        && allBuilderMethodNames.contains(name + "Builder")) {
      return false;
    }
    // if it's an Optional, the Builder will automatically initialize it
    if (isOptional(returnType)) {
      return false;
    }
    // it's required!
    return true;
  }

  /**
   * Ignore java.lang.Object overrides, constructors, and toBuilder method in AutoValue classes.
   *
   * <p>Strictly speaking we should probably be checking return types, etc. here to handle strange
   * overloads and other corner cases. They seem unlikely enough that we are skipping for now.
   */
  private static final ImmutableSet<String> IGNORED_METHOD_NAMES =
      ImmutableSet.of("equals", "hashCode", "toString", "<init>", "toBuilder");
  /** Taken from AutoValue source code */
  private static final ImmutableSet<String> OPTIONAL_CLASS_NAMES =
      ImmutableSet.of(
          "com.google.common.base.Optional",
          "java.util.Optional",
          "java.util.OptionalDouble",
          "java.util.OptionalInt",
          "java.util.OptionalLong");

  /**
   * adapted from AutoValue source code
   *
   * @param type some type
   * @return true if type is an Optional type
   */
  static boolean isOptional(TypeMirror type) {
    if (type.getKind() != TypeKind.DECLARED) {
      return false;
    }
    DeclaredType declaredType = (DeclaredType) type;
    TypeElement typeElement = (TypeElement) declaredType.asElement();
    return OPTIONAL_CLASS_NAMES.contains(typeElement.getQualifiedName().toString())
        && typeElement.getTypeParameters().size() == declaredType.getTypeArguments().size();
  }

  /**
   * Computes the names of setter methods for an AutoValue builder
   *
   * @param builderElement Element for an AutoValue builder
   * @return names of all methods whose return type is the builder itself or that return a Guava
   *     Immutable type
   */
  private Set<String> getAutoValueBuilderSetterMethodNames(Element builderElement) {
    return getAllAbstractMethods(builderElement).stream()
        .filter(e -> isAutoValueBuilderSetter(e, builderElement))
        .map(e -> e.getSimpleName().toString())
        .collect(Collectors.toSet());
  }

  /**
   * Is member a setter for an AutoValue builder?
   *
   * @param member member of builder or one of its supertypes
   * @param builderElement element for the AutoValue builder
   * @return {@code true} if e is a setter for the builder, {@code false} otherwise
   */
  private boolean isAutoValueBuilderSetter(Element member, Element builderElement) {
    TypeMirror retType = ((ExecutableElement) member).getReturnType();
    if (retType.getKind().equals(TypeKind.TYPEVAR)) {
      // instantiate the type variable for the Builder class
      retType =
          AnnotatedTypes.asMemberOf(
                  atypeFactory.getContext().getTypeUtils(),
                  atypeFactory,
                  atypeFactory.getAnnotatedType(builderElement),
                  (ExecutableElement) member)
              .getReturnType()
              .getUnderlyingType();
    }
    // either the return type should be the builder itself, or it should be a Guava immutable type
    return FrameworkSupportUtils.isGuavaImmutableType(retType)
        || builderElement.equals(TypesUtils.getTypeElement(retType));
  }

  /**
   * Get all the abstract methods for a class. This should include those inherited abstract methods
   * that are not overridden by the class or a superclass
   *
   * @param classElement the class
   * @return list of all abstract methods
   */
  public List<Element> getAllAbstractMethods(Element classElement) {
    List<Element> supertypes = getAllSupertypes((Symbol) classElement);
    List<Element> abstractMethods = new ArrayList<>();
    Set<Element> overriddenMethods = new HashSet<>();
    for (Element t : supertypes) {
      for (Element member : t.getEnclosedElements()) {
        if (!member.getKind().equals(ElementKind.METHOD)) {
          continue;
        }
        Set<Modifier> modifiers = member.getModifiers();
        if (modifiers.contains(Modifier.STATIC)) {
          continue;
        }
        if (modifiers.contains(Modifier.ABSTRACT)) {
          // make sure it's not overridden
          if (!overriddenMethods.contains(member)) {
            abstractMethods.add(member);
          }
        } else {
          // exclude any methods that this overrides
          overriddenMethods.addAll(
              AnnotatedTypes.overriddenMethods(
                      atypeFactory.getElementUtils(), atypeFactory, (ExecutableElement) member)
                  .values());
        }
      }
    }
    return abstractMethods;
  }

  /**
   * @param symbol symbol for a class
   * @return list including the class and all its supertypes, with a guarantee that subtypes appear
   *     before supertypes
   */
  private List<Element> getAllSupertypes(Symbol symbol) {
    Types types =
        Types.instance(((JavacProcessingEnvironment) atypeFactory.getProcessingEnv()).getContext());
    return types.closure(symbol.type).stream().map(t -> t.tsym).collect(Collectors.toList());
  }
}
