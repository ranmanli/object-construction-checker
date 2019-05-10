package org.checkerframework.checker.builder;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import org.checkerframework.checker.builder.qual.CalledMethods;
import org.checkerframework.checker.builder.qual.CalledMethodsBottom;
import org.checkerframework.checker.builder.qual.CalledMethodsPredicate;
import org.checkerframework.checker.builder.qual.CalledMethodsTop;
import org.checkerframework.checker.builder.qual.ReturnsReceiver;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.TreeUtils;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The annotated type factory for the typesafe builder checker. Primarily responsible
 * for the subtyping rules between @CalledMethod annotations.
 */
public class TypesafeBuilderAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

    /**
     * Canonical copies of the top and bottom annotations. Package private to permit access
     * from the Transfer class.
     */
    final AnnotationMirror TOP, BOTTOM;

    /**
     * Default constructor matching super. Should be called automatically.
     */
    public TypesafeBuilderAnnotatedTypeFactory(final BaseTypeChecker checker) {
        super(checker);
        TOP = AnnotationBuilder.fromClass(elements, CalledMethodsTop.class);
        BOTTOM = AnnotationBuilder.fromClass(elements, CalledMethodsBottom.class);
        System.out.println("ATF was initialized");
        this.postInit();
    }

    /** Creates a @CalledMethods annotation whose values are the given strings. */
    public AnnotationMirror createCalledMethods(final String... val) {
        if (val.length == 0) {
            return TOP;
        }
        AnnotationBuilder builder = new AnnotationBuilder(processingEnv, CalledMethods.class);
        Arrays.sort(val);
        builder.setValue("value", val);
        return builder.build();
    }

    /**
     * Wrapper to accept a List of Strings instead of an array if that's convenient at the call site.
     */
    public AnnotationMirror createCalledMethods(final List<String> valList) {
        String[] vals = valList.toArray(new String[0]);
        return createCalledMethods(vals);
    }

    @Override
    public TreeAnnotator createTreeAnnotator() {
        return new ListTreeAnnotator(super.createTreeAnnotator(), new TypesafeBuilderTreeAnnotator(this));
    }

    @Override
    public QualifierHierarchy createQualifierHierarchy(final MultiGraphQualifierHierarchy.MultiGraphFactory factory) {
        return new TypesafeBuilderQualifierHierarchy(factory);
    }

    /**
     * This tree annotator is needed to create types for fluent builders
     * that have @ReturnsReceiver annotations.
     */
    private class TypesafeBuilderTreeAnnotator extends TreeAnnotator {
        public TypesafeBuilderTreeAnnotator(final AnnotatedTypeFactory atypeFactory) {
            super(atypeFactory);
        }

        @Override
        public Void visitMethodInvocation(final MethodInvocationTree tree, final AnnotatedTypeMirror type) {

            // Check to see if the @ReturnsReceiver annotation is present
            Element element = TreeUtils.elementFromUse(tree);
            AnnotationMirror returnsReceiver = getDeclAnnotation(element, ReturnsReceiver.class);

            if (returnsReceiver != null) {

                // Fetch the current type of the receiver, or top if none exists
                ExpressionTree receiverTree = TreeUtils.getReceiverTree(tree.getMethodSelect());
                AnnotatedTypeMirror receiverType = getAnnotatedType(receiverTree);
                AnnotationMirror receiverAnno = receiverType.getAnnotationInHierarchy(TOP);
                if (receiverAnno == null) {
                    receiverAnno = TOP;
                }

                // Construct a new @CM annotation with just the method name
                String methodName = TreeUtils.methodName(tree).toString();
                AnnotationMirror cmAnno = createCalledMethods(methodName);

                // Replace the return type of the method with the GLB (= union) of the two types above
                AnnotationMirror newAnno = getQualifierHierarchy().greatestLowerBound(cmAnno, receiverAnno);
                type.replaceAnnotation(newAnno);
            }

            return super.visitMethodInvocation(tree, type);
        }

        @Override
        public Void visitClass(final ClassTree tree, final AnnotatedTypeMirror type) {
            System.out.println(tree);
            return super.visitClass(tree, type);
        }

        @Override
        public Void visitMethod(final MethodTree node, final AnnotatedTypeMirror type) {
            // TODO: Lombok @Generated builder classes should have the following type annotations placed automatically:
            // on setters: @ReturnsReceiver as a decl annotation
            // on the finalizer (build()) method: @CalledMethods(A), where A is the set of lombok.@NonNull fields

            // Was the class that contains this method generated by Lombok?
            boolean lombokGenerated = false;

            ClassTree enclosingClass = TreeUtils.enclosingClass(getPath(node));

            for (AnnotationTree atree : enclosingClass.getModifiers().getAnnotations()) {
                AnnotationMirror anm = TreeUtils.annotationFromAnnotationTree(atree);
                if (AnnotationUtils.areSameByClass(anm, lombok.Generated.class)) {
                    lombokGenerated = true;
                }
            }

            System.out.println("was " + enclosingClass.getSimpleName().toString() + " generated by lombok? " + lombokGenerated);

            // if this class was generated by Lombok, then we know:
            // - is this a setter method? If so, we need to add an @ReturnsReceiver annotation
            // - is this the finalizer method? If so, we need to add an @CalledMethods annotation
            //   to its receiver
            if (lombokGenerated) {
                // get the name of the method
                String methodName = TreeUtils.getMethodName(node);

                boolean isFinalizer = "build".equals(methodName);

                // get the names of the fields
                List<String> fieldNames = Collections.emptyList();
                for (Tree member : enclosingClass.getMembers()) {
                    if (member.getKind() == Tree.Kind.VARIABLE) {
                        VariableTree fieldTree = (VariableTree) member;
                        if (isFinalizer) {
                            // for finalizers, only add the names of fields annotated with lombok.NonNull
                            for (AnnotationTree atree : fieldTree.getModifiers().getAnnotations()) {
                                AnnotationMirror anm = TreeUtils.annotationFromAnnotationTree(atree);
                                if (AnnotationUtils.areSameByClass(anm, lombok.NonNull.class)) {
                                    fieldNames.add(fieldTree.getName().toString());
                                }
                            }
                        } else {
                            // for other methods, we only care if this is a setter (which we will deduce by
                            // checking if the method name is also a field name
                            fieldNames.add(fieldTree.getName().toString());
                        }
                    }
                }

                if (isFinalizer) {
                    // if its a finalizer, add the @CalledMethods annotation with the field names
                    // to the receiver
                    VariableTree receiverTree = node.getReceiverParameter();
                    AnnotationMirror newCalledMethodsAnno = createCalledMethods(fieldNames);
                    System.out.println("adding this annotation " + newCalledMethodsAnno + " to the receiver of this method " + methodName);
                    getAnnotatedType(receiverTree).addAnnotation(newCalledMethodsAnno);
                } else if (fieldNames.contains(methodName)) {
                    AnnotationMirror newReturnsReceiverAnno =
                            AnnotationBuilder.fromClass(elements, ReturnsReceiver.class);
                    System.out.println("adding @ReturnsReceiver to this method " + methodName);
                    getAnnotatedType(node).addAnnotation(newReturnsReceiverAnno);
                }
            }
            return super.visitMethod(node, type);
        }
    }

    /**
     * The qualifier hierarchy is responsible for lub, glb, and subtyping between qualifiers without
     * declaratively defined subtyping relationships, like our @CalledMethods annotation.
     */
    private class TypesafeBuilderQualifierHierarchy extends MultiGraphQualifierHierarchy {
        public TypesafeBuilderQualifierHierarchy(final MultiGraphQualifierHierarchy.MultiGraphFactory factory) {
            super(factory);
        }

        @Override
        public AnnotationMirror getTopAnnotation(final AnnotationMirror start) {
            return TOP;
        }

        /**
         * GLB in this type system is set union of the arguments of the two annotations,
         * unless one of them is bottom, in which case the result is also bottom.
         */
        @Override
        public AnnotationMirror greatestLowerBound(final AnnotationMirror a1, final AnnotationMirror a2) {
            if (AnnotationUtils.areSame(a1, BOTTOM) || AnnotationUtils.areSame(a2, BOTTOM)) {
                return BOTTOM;
            }

            if (!AnnotationUtils.hasElementValue(a1, "value")) {
                return a2;
            }

            if (!AnnotationUtils.hasElementValue(a2, "value")) {
                return a1;
            }

            Set<String> a1Val = new LinkedHashSet<>(getValueOfAnnotationWithStringArgument(a1));
            Set<String> a2Val = new LinkedHashSet<>(getValueOfAnnotationWithStringArgument(a2));
            a1Val.addAll(a2Val);
            return createCalledMethods(a1Val.toArray(new String[0]));
        }

        /**
         * LUB in this type system is set intersection of the arguments of the two annotations,
         * unless one of them is bottom, in which case the result is the other annotation.
         */
        @Override
        public AnnotationMirror leastUpperBound(final AnnotationMirror a1, final AnnotationMirror a2) {
            if (AnnotationUtils.areSame(a1, BOTTOM)) {
                return a2;
            } else if (AnnotationUtils.areSame(a2, BOTTOM)) {
                return a1;
            }

            if (!AnnotationUtils.hasElementValue(a1, "value")) {
                return a1;
            }

            if (!AnnotationUtils.hasElementValue(a2, "value")) {
                return a2;
            }

            Set<String> a1Val = new LinkedHashSet<>(getValueOfAnnotationWithStringArgument(a1));
            Set<String> a2Val = new LinkedHashSet<>(getValueOfAnnotationWithStringArgument(a2));
            a1Val.retainAll(a2Val);
            return createCalledMethods(a1Val.toArray(new String[0]));
        }

        /**
         * isSubtype in this type system is subset
         */
        @Override
        public boolean isSubtype(final AnnotationMirror subAnno, final AnnotationMirror superAnno) {
            if (AnnotationUtils.areSame(subAnno, BOTTOM)) {
                return true;
            } else if (AnnotationUtils.areSame(superAnno, BOTTOM)) {
                return false;
            }

            if (AnnotationUtils.areSame(superAnno, TOP)) {
                return true;
            } else if (AnnotationUtils.areSame(subAnno, TOP)) {
                return false;
            }

            if (AnnotationUtils.areSameByClass(subAnno, CalledMethodsPredicate.class)) {
                return false;
            }

            Set<String> subVal = new LinkedHashSet<>(getValueOfAnnotationWithStringArgument(subAnno));

            if (AnnotationUtils.areSameByClass(superAnno, CalledMethodsPredicate.class)) {
                // superAnno is a CMP annotation, so we need to evaluate the predicate
                String predicate = AnnotationUtils.getElementValue(superAnno, "value", String.class, false);
                CalledMethodsPredicateEvaluator evaluator = new CalledMethodsPredicateEvaluator(subVal);
                String result = evaluator.evaluate(predicate);
                return Boolean.parseBoolean(result);
            } else {
                // superAnno is a CM annotation, so compare the sets
                return subVal.containsAll(getValueOfAnnotationWithStringArgument(superAnno));
            }
        }
    }

    /**
     * Gets the value field of an annotation with a list of strings in its value field.
     * The empty list is returned if no value field is defined.
     */
    public static List<String> getValueOfAnnotationWithStringArgument(final AnnotationMirror anno) {
        if (!AnnotationUtils.hasElementValue(anno, "value")) {
            return Collections.emptyList();
        }
        return AnnotationUtils.getElementValueArray(anno, "value", String.class, true);
    }
}
