/*
 * Copyright (C) 2013 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.hikari.javassist;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;
import javassist.bytecode.ClassFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Brett Wooldridge
 */
public class HikariClassTransformer implements ClassFileTransformer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(HikariClassTransformer.class);

    private static Instrumentation ourInstrumentation;
    private static HikariClassTransformer transformer;
    private static ClassPool classPool;

    private String sniffPackage;

    private volatile boolean agentFailed;

    /**
     * Private constructor.
     * 
     * @param sniffPackage the package name used to filter only classes we are interested in
     */
    private HikariClassTransformer(String sniffPackage)
    {
        this.sniffPackage = sniffPackage;
        HikariClassTransformer.transformer = this;
    }

    /**
     * The method that is called when VirtualMachine.loadAgent() is invoked to register our
     * class transformer.
     *
     * @param agentArgs arguments to pass to the agent
     * @param inst the virtual machine Instrumentation instance used to register our transformer 
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation)
    {
        ourInstrumentation = instrumentation;

        ClassPool defaultPool = ClassPool.getDefault();
        classPool = new ClassPool(defaultPool);
        classPool.importPackage("java.sql");
        classPool.childFirstLookup = true;

        ourInstrumentation.addTransformer(new HikariClassTransformer(agentArgs), false);
    }

    /** {@inheritDoc} */
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException
    {
        if (!className.startsWith(sniffPackage))
        {
            return classfileBuffer;
        }

        try
        {
            ClassFile classFile = new ClassFile(new DataInputStream(new ByteArrayInputStream(classfileBuffer)));
            for (String iface : classFile.getInterfaces())
            {
                if (!iface.startsWith("java.sql"))
                {
                    continue;
                }

                if (iface.equals("java.sql.Connection"))
                {
                    return transformConnection(classFile);
                }
                else if (iface.equals("java.sql.PreparedStatement"))
                {
                    return transformPreparedStatement(classFile);
                }
                else if (iface.equals("java.sql.CallableStatement"))
                {
                    return transformCallableStatement(classFile);
                }
                else if (iface.equals("java.sql.Statement"))
                {
                    return transformStatement(classFile);
                }
                else if (iface.equals("java.sql.ResultSet"))
                {
                    return transformResultSet(classFile);
                }
            }

            // None of the interfaces we care about were found, so just return the class file buffer
            return classfileBuffer;
        }
        catch (Exception e)
        {
            agentFailed = true;
            LOGGER.error("Error transforming class {}", className, e);
            return classfileBuffer;
        }
    }

    public boolean isAgentFailed()
    {
        return agentFailed;
    }

    /**
     * @param classFile
     */
    private byte[] transformConnection(ClassFile classFile) throws Exception
    {
        String className = classFile.getName();
        CtClass target = classPool.getCtClass(className);

        CtClass intf = classPool.get("com.zaxxer.hikari.proxy.IHikariConnectionProxy");
        target.addInterface(intf);
        LOGGER.debug("Added interface {} to {}", intf.getName(), className);

        CtClass proxy = classPool.get("com.zaxxer.hikari.proxy.ConnectionProxy");

        copyFields(proxy, target);
        copyMethods(proxy, target, classFile);
        mergeClassInitializers(proxy, target, classFile);

        for (CtConstructor constructor : target.getConstructors())
        {
            constructor.insertAfter("__init();");
        }

        target.debugWriteFile("/tmp");
        return target.toBytecode();
    }

    /**
     * @param classFile
     */
    private byte[] transformPreparedStatement(ClassFile classFile) throws Exception
    {
        String className = classFile.getName();
        CtClass target = classPool.getCtClass(className);

        CtClass intf = classPool.get("com.zaxxer.hikari.proxy.IHikariStatementProxy");
        target.addInterface(intf);
        LOGGER.debug("Added interface {} to {}", intf.getName(), className);

        CtClass proxy = classPool.get("com.zaxxer.hikari.proxy.PreparedStatementProxy");

        copyFields(proxy, target);
        copyMethods(proxy, target, classFile);
        mergeClassInitializers(proxy, target, classFile);

        target.debugWriteFile("/tmp");
        return target.toBytecode();
    }

    /**
     * @param classFile
     */
    private byte[] transformCallableStatement(ClassFile classFile) throws Exception
    {
        String className = classFile.getName();
        CtClass target = classPool.getCtClass(className);

        CtClass intf = classPool.get("com.zaxxer.hikari.proxy.IHikariStatementProxy");
        target.addInterface(intf);
        LOGGER.debug("Added interface {} to {}", intf.getName(), className);

        CtClass proxy = classPool.get("com.zaxxer.hikari.proxy.CallableStatementProxy");

        copyFields(proxy, target);
        copyMethods(proxy, target, classFile);
        mergeClassInitializers(proxy, target, classFile);

        target.debugWriteFile("/tmp");
        return target.toBytecode();
    }

    /**
     * @param classFile
     */
    private byte[] transformStatement(ClassFile classFile) throws Exception
    {
        String className = classFile.getName();
        CtClass target = classPool.getCtClass(className);

        CtClass intf = classPool.get("com.zaxxer.hikari.proxy.IHikariStatementProxy");
        target.addInterface(intf);
        LOGGER.debug("Added interface {} to {}", intf.getName(), className);

        CtClass proxy = classPool.get("com.zaxxer.hikari.proxy.StatementProxy");

        copyFields(proxy, target);
        copyMethods(proxy, target, classFile);
        mergeClassInitializers(proxy, target, classFile);

        target.debugWriteFile("/tmp");
        return target.toBytecode();
    }

    /**
     * @param classFile
     */
    private byte[] transformResultSet(ClassFile classFile) throws Exception
    {
        String className = classFile.getName();
        CtClass target = classPool.getCtClass(className);

        CtClass intf = classPool.get("com.zaxxer.hikari.proxy.IHikariResultSetProxy");
        target.addInterface(intf);
        LOGGER.debug("Added interface {} to {}", intf.getName(), className);

        CtClass proxy = classPool.get("com.zaxxer.hikari.proxy.ResultSetProxy");

        copyFields(proxy, target);
        copyMethods(proxy, target, classFile);
        mergeClassInitializers(proxy, target, classFile);

        target.debugWriteFile("/tmp");
        return target.toBytecode();
    }

    private void copyFields(CtClass srcClass, CtClass destClass) throws Exception
    {
        HashSet<CtField> srcFields = new HashSet<CtField>();
        srcFields.addAll(Arrays.asList(srcClass.getDeclaredFields()));
        srcFields.addAll(Arrays.asList(srcClass.getFields()));
        for (CtField field : srcFields)
        {
            if (field.getAnnotation(HikariInject.class) == null)
            {
                LOGGER.debug("Skipped field {}", field.getName());
                continue;
            }

            CtField copy = new CtField(field.getType(), field.getName(), destClass);
            copy.setModifiers(field.getModifiers());
            destClass.addField(copy);
            LOGGER.debug("Copied field {}.{} to {}", srcClass.getSimpleName(), field.getName(), destClass.getSimpleName());
        }
    }

    private void copyMethods(CtClass srcClass, CtClass destClass, ClassFile destClassFile) throws Exception
    {
        CtMethod[] destMethods = destClass.getMethods();

        HashSet<CtMethod> srcMethods = new HashSet<CtMethod>();
        srcMethods.addAll(Arrays.asList(srcClass.getMethods()));
        srcMethods.addAll(Arrays.asList(srcClass.getDeclaredMethods()));
        for (CtMethod method : srcMethods)
        {
            if (method.getAnnotation(HikariInject.class) == null)
            {
                LOGGER.debug("Skipped method {}", method.getName());
                continue;
            }

            if (destClassFile.getMethod(method.getName()) != null)  // maybe we have a name collision
            {
                String signature = method.getSignature();
                for (CtMethod destMethod : destMethods)
                {
                    if (destMethod.getName().equals(method.getName()) && destMethod.getSignature().equals(signature))
                    {
                        LOGGER.debug("Rename method {}.{} to __{}", destClass.getSimpleName(), destMethod.getName(), destMethod.getName());
                        destMethod.setName("__" + destMethod.getName());
                        break;
                    }
                }
            }

            CtMethod copy = CtNewMethod.copy(method, destClass, null);
            destClass.addMethod(copy);
            LOGGER.debug("Copied method {}.{} to {}", srcClass.getSimpleName(), method.getName(), destClass.getSimpleName());
        }
    }

    private void mergeClassInitializers(CtClass srcClass, CtClass destClass, ClassFile destClassFile) throws Exception
    {
        CtConstructor srcInitializer = srcClass.getClassInitializer();
        if (srcInitializer == null)
        {
            return;
        }

        CtConstructor destInitializer = destClass.getClassInitializer();
        if (destInitializer == null && srcInitializer != null)
        {
            CtConstructor copy = CtNewConstructor.copy(srcInitializer, destClass, null);
            destClass.addConstructor(copy);
            LOGGER.debug("Copied static initializer of {} to {}", srcClass.getSimpleName(), destClass.getSimpleName());
        }
        else
        {
            CtMethod method = destInitializer.toMethod("__static", destClass);
            destClass.addMethod(method);
            destClass.removeConstructor(destInitializer);
            LOGGER.debug("Move static initializer of {}", destClass.getSimpleName());
            mergeClassInitializers(srcClass, destClass, destClassFile);
        }
    }

    /**
     * 
     */
    static void unregisterInstrumenation()
    {
        ourInstrumentation.removeTransformer(transformer);
    }
}