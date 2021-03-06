package se.kth.castor.yajta.api;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtPrimitiveType;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.Bytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Mnemonic;
import javassist.bytecode.Opcode;
import javassist.bytecode.analysis.ControlFlow;
import javassist.compiler.CompileError;
import javassist.compiler.Javac;
import se.kth.castor.yajta.TracerI;
import se.kth.castor.yajta.Utils;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;

public class SimpleTracer extends AbstractTracer implements TracerI {

    public static boolean strictIncludes = false;

    String loggerInstance;
    boolean logValue = false;
    boolean logBranch = false;


    public SimpleTracer (ClassList cl) {
        this.cl = cl;
        this.loggerInstance = "se.kth.castor.yajta.Agent.getInstance()";
        this.logValue = false;
        //new SimpleTracer(cl, "se.kth.castor.yajta.Agent.getInstance()", false);
    }

    public SimpleTracer (ClassList cl, String loggerInstance) {
        this.cl = cl;
        this.loggerInstance = loggerInstance;
        this.logValue = false;
        //new SimpleTracer(cl, loggerInstance, false);
    }

    public SimpleTracer (ClassList cl, boolean logValue) {
        this.cl = cl;
        this.loggerInstance = "se.kth.castor.yajta.Agent.getInstance()";
        this.logValue = logValue;
        //new SimpleTracer(cl, "se.kth.castor.yajta.Agent.getInstance()", logValue);
    }

    public SimpleTracer (ClassList cl, String loggerInstance, boolean logValue) {
        this.cl = cl;
        this.loggerInstance = loggerInstance;
        this.logValue = logValue;
    }


    public boolean implementsInterface(Class cl, Class interf) {
        for(Class c : cl.getInterfaces()) {
            if(c.equals(interf)) {
                return true;
            }
        }
        return false;
    }

    public void setTrackingClass(Class<? extends Tracking> trackingClass) throws MalformedTrackingClassException {
        if(verbose) System.err.println( "[yajta] setTrackingClass " + trackingClass.getName());
        if(trackingClass.isAnonymousClass()) {
            throw new MalformedTrackingClassException("Class " + trackingClass.getName() + " should not be anonymous.)");
        }
        try {
            Method m = trackingClass.getDeclaredMethod("getInstance");
            if(!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                throw new MalformedTrackingClassException("Method " + trackingClass.getName() + ".getInstance() is not static");
            }
            loggerInstance = trackingClass.getName() + ".getInstance()";
            logValue = false;
            if(implementsInterface(trackingClass, BranchTracking.class)) {
                if(verbose) System.err.println( "[yajta] set Branch tracking on.");
                logBranch = true;
            }
        } catch (NoSuchMethodException e) {
            throw new MalformedTrackingClassException("Class " + trackingClass.getName() + " does not have a static method getInstance()");
        }
    }

    public void setValueTrackingClass(Class<? extends ValueTracking> trackingClass) throws MalformedTrackingClassException {
        if(trackingClass.isAnonymousClass()) {
            throw new MalformedTrackingClassException("Class " + trackingClass.getName() + " should not be anonymous.)");
        }
        try {
            Method m = trackingClass.getDeclaredMethod("getInstance");
            if(!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                throw new MalformedTrackingClassException("Method " + trackingClass.getName() + ".getInstance() is not static");
            }
            loggerInstance = trackingClass.getName() + ".getInstance()";
            logValue = true;
            if(implementsInterface(trackingClass, BranchTracking.class)) {
                logBranch = true;
            }
        } catch (NoSuchMethodException e) {
            throw new MalformedTrackingClassException("Class " + trackingClass.getName() + " does not have a static method getInstance()");
        }
    }

    @Override
    public void setFastTrackingClass(Class<? extends FastTracking> trackingClass) throws MalformedTrackingClassException {
        throw new UnsupportedOperationException("SimpleTracer only supports TracingInstance that implements ValueTracking or Tracking");
    }

    private Bytecode getBytecode(String print, CtClass cc) throws CompileError {
        Javac jv = new Javac(cc);
        jv.compileStmnt(print);
        if(verbose) System.err.println("compile: " + print);
        return jv.getBytecode();
    }

    private void printBlock(CodeIterator iterator, int begin, int end, int no) {
        System.out.println(" -- Block "+ no +":");
        for(int i = begin; i < end; i++) {
            //if(iterator.byteAt(i) != 0)
                System.out.println(Mnemonic.OPCODE[iterator.byteAt(i)]);
        }
        System.out.println(" -- ");
    }

    private void printDiff(CodeAttribute ca, int debut, int insertSize, int segmentSize) {
        System.out.println(" --- RAW --- ");

        for(int i = 0; i < ca.getCode().length; i++) {
            if(i >= debut && i < debut + insertSize) {
                System.out.print("++");
            }
            if(i >= debut + insertSize && i < debut + segmentSize) {
                System.out.print("|");
            }

            //if(ca.getCode()[i] != 0) {
                System.out.println(Utils.getOpcode(ca.getCode()[i] & 0xff));
            //}
        }
    }

    private boolean isBlockEmpty(CodeIterator iterator, int begin, int end) {
        for(int i = begin; i < end; i++) {
            if(iterator.byteAt(i) != 0) return false;
        }
        return true;
    }

    protected void doMethod( final CtBehavior method , String className, boolean isIsotope, String isotope) throws NotFoundException {
        //System.out.println("\t\tMethod: " + method.getLongName() + " -> " + !Modifier.isNative(method.getModifiers()));
        if(!Modifier.isNative(method.getModifiers()) && !Modifier.isAbstract(method.getModifiers())) {
            try {
                if(verbose) System.err.println("[Vanilla] " + className + " " + method.getName());
                /*String params = "(";
                boolean first = true;
                for (CtClass c : method.getParameterTypes()) {
                    if (first) first = false;
                    else params += ", ";
                    params += c.getName();
                }
                params += ")";*/

                String parameterValues = "";
                String returnValue = "";
                if(logValue) {
                    parameterValues = ", $args";
                    if(method instanceof CtMethod) {
                        CtMethod m = (CtMethod) method;
                        if(m.getReturnType() instanceof CtPrimitiveType
                                && !(m.getReturnType().getName().equals("void"))) {
                            returnValue = ", new " + ((CtPrimitiveType) m.getReturnType()).getWrapperName() +"($_)";
                        } else {
                            returnValue = ", $_";
                        }
                    } else {
                        returnValue = ", $_";
                    }
                }

                /*System.err.println(loggerInstance + ".stepIn(Thread.currentThread().getName(),\""
                        + className.replace("/", ".") + "\", \""
                        + method.getName() + params + "\""
                        + parameterValues
                        + ");");*/
                /*method.insertBefore(loggerInstance + ".stepIn(Thread.currentThread().getName(),\""
                        + className.replace("/", ".") + "\", \""
                        + method.getName() + params + "\""
                        + parameterValues
                        + ");");
                method.insertAfter(loggerInstance + ".stepOut(Thread.currentThread().getName()"
                        + returnValue
                        +");");*/

                // !!!! This only work because the inserted call for branch logging does not have more arguments than the method logging one.
                if(logBranch && method instanceof CtMethod) {
                    try {
                        ControlFlow controlFlow = new ControlFlow((CtMethod)method);
                        MethodInfo info = method.getMethodInfo();
                        CodeAttribute ca = info.getCodeAttribute();
                        CodeIterator iterator = ca.iterator();
                        ControlFlow.Block[] blocks = controlFlow.basicBlocks();
                        String branchIn = loggerInstance + ".branchIn(Thread.currentThread().getName(),\"";
                        String branchInEnd = "\");";
                        String branchOut = loggerInstance + ".branchOut(Thread.currentThread().getName());";
                        int offset = 0;
                        /*if(method.getName().equals("myIfElse")) {
                            System.out.println(" --- RAW --- ");

                            for(int i = 0; i < ca.getCode().length; i++) {
                                System.out.println(Mnemonic.OPCODE[(int) ca.getCode()[i] & 0xff]);
                            }

                            System.out.println(" --- Avant --- ");
                            for(int i = 0; i < blocks.length; i++) {
                                printBlock(iterator, blocks[i].position(), blocks[i].position() + blocks[i].length(), i);
                            }
                            System.out.println(" --- Après --- ");
                            System.out.println("------- total size: "+ca.getCode().length+ ", added 0");
                        }*/
                        for(int i = 0; i < blocks.length; i++) {
                        //for(int i = 1; i < blocks.length-1; i++) {
                            int sizeBefore = ca.getCode().length;
                            String inser = branchIn + i + branchInEnd;
                            /*if(i == 0) {
                                inser = loggerInstance + ".stepIn(Thread.currentThread().getName(),\""
                                        + className.replace("/", ".") + "\", \""
                                        + method.getName() + params + "\""
                                        + parameterValues
                                        + ");\n" + inser;
                            }*/
                            byte[] bytes = getBytecode(inser, method.getDeclaringClass()).get();
                            iterator.insertAt(blocks[i].position() + offset, bytes);
                            int old_off = offset;
                            //offset += bytes.length;
                            int sizeAfter = ca.getCode().length;

                            offset += (sizeAfter - sizeBefore); //insertAt may insert more than bytes.length bytes... For some reasons...

                            /*if(method.getName().equals("myIfElse")) {
                                System.out.println("------- total size: " + ca.getCode().length
                                        + ", added " + (sizeAfter - sizeBefore)
                                        + ", from " + (blocks[i].position() + old_off)
                                        + ", to " + ((sizeAfter - sizeBefore) + blocks[i].length()));
                                //printBlock(iterator, blocks[i].position() + old_off, blocks[i].position() + offset + blocks[i].length(), i);
                                printDiff(ca, blocks[i].position() + old_off, (sizeAfter - sizeBefore), (sizeAfter - sizeBefore) + blocks[i].length());
                            }*/

                            //Branch out
                            /*sizeBefore = ca.getCode().length;
                            bytes = getBytecode(branchOut, method.getDeclaringClass()).get();
                            //iterator.insertAt(blocks[i].position() + offset + blocks[i].length(), bytes);
                            iterator.append(bytes);
                            sizeAfter = ca.getCode().length;
                            offset += (sizeAfter - sizeBefore);*/
                        }
                        ca.computeMaxStack();
                        //if(ms < 4) ca.setMaxStack(4);
                    } catch (BadBytecode badBytecode) {
                        badBytecode.printStackTrace();
                    } catch (CompileError compileError) {
                        compileError.printStackTrace();
                    }
                } else {
                    /*method.insertBefore(loggerInstance + ".stepIn(Thread.currentThread().getName(),\""
                            + className.replace("/", ".") + "\", \""
                            + method.getName() + params + "\""
                            + parameterValues
                            + ");");*/
                }
                /*method.insertBefore(loggerInstance + ".stepIn(Thread.currentThread().getName(),\""
                        + className.replace("/", ".") + "\", \""
                        + method.getName() + params + "\""
                        + parameterValues
                        + ");");*/
                method.insertBefore(loggerInstance + ".stepIn(Thread.currentThread().getName(),\""
                        + className.replace("/", ".") + "\", \""
                        + method.getName() + method.getSignature() + "\""
                        + parameterValues
                        + ");");
                method.insertAfter(loggerInstance + ".stepOut(Thread.currentThread().getName()"
                        + returnValue
                        +");");
            } catch (CannotCompileException e) {
                e.printStackTrace();
                System.err.println("[Yajta] Cannot insert probe in " + method.getDeclaringClass().getName() + " " + method.getName() + method.getSignature() + ", skipping method");
            }

        } else {
            if(verbose) System.err.println("Method: " + className.replace("/", ".") + "." + method.getName() + " is native");
        }
    }
}
