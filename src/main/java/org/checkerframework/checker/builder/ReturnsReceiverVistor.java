package org.checkerframework.checker.builder;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.source.Result;

import com.sun.source.tree.ReturnTree;

public class ReturnsReceiverVistor extends BaseTypeVisitor<TypesafeBuilderAnnotatedTypeFactory>{

	public ReturnsReceiverVistor(BaseTypeChecker checker) {
		super(checker);
		// TODO Auto-generated constructor stub
	}

	@Override
	public Void visitReturn(ReturnTree node, Void p) {

        // if the method is void, report violation
        try{
	        if (node.getExpression() == null || !(node.getExpression().toString().equals("this"))) {
        		System.out.printf("returning: "+node.getExpression().toString()+"\n");
	        	throw new IllegalArgumentException();
	        }       	
        }
        catch (IllegalArgumentException e){
	        checker.report(Result.failure("return.receiver.incompatible"), node);
	        return null;
        }
        return super.visitReturn(node, p);
	}
}