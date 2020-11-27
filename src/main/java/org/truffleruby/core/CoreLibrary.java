/*
 * Copyright (c) 2013, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.oracle.truffle.api.object.Shape;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.transcode.EConvFlags;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.SuppressFBWarnings;
import org.truffleruby.aot.ParserCache;
import org.truffleruby.builtins.BuiltinsClasses;
import org.truffleruby.builtins.CoreMethodNodeManager;
import org.truffleruby.core.array.RubyArray;
import org.truffleruby.core.array.library.ArrayStoreLibrary;
import org.truffleruby.core.basicobject.RubyBasicObject;
import org.truffleruby.core.binding.RubyBinding;
import org.truffleruby.core.klass.ClassNodes;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.core.module.ModuleNodes;
import org.truffleruby.core.module.RubyModule;
import org.truffleruby.core.numeric.BigIntegerOps;
import org.truffleruby.core.numeric.RubyBignum;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.debug.GlobalVariablesObject;
import org.truffleruby.debug.TopScopeObject;
import org.truffleruby.extra.ffi.Pointer;
import org.truffleruby.language.Nil;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.RubyDynamicObject;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyRootNode;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.globals.GlobalVariableReader;
import org.truffleruby.language.globals.GlobalVariables;
import org.truffleruby.language.loader.CodeLoader;
import org.truffleruby.language.loader.FileLoader;
import org.truffleruby.language.loader.ResourceLoader;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.methods.SharedMethodInfo;
import org.truffleruby.language.objects.SingletonClassNode;
import org.truffleruby.parser.ParserContext;
import org.truffleruby.parser.RubySource;
import org.truffleruby.parser.TranslatorDriver;
import org.truffleruby.parser.ast.RootParseNode;
import org.truffleruby.platform.NativeConfiguration;
import org.truffleruby.platform.NativeTypes;
import org.truffleruby.shared.BuildInformationImpl;
import org.truffleruby.shared.TruffleRuby;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/** When adding a new class (MyClass) to the core library, you need to:
 * <ul>
 * <li>Add any Ruby file to {@link #CORE_FILES}.
 *
 * <li>Create a CoreLibrary field for the class ({@code myClassClass}) and initialize it using {@link #defineClass}. See
 * examples in this file.
 *
 * <li>If the class has Java-defined nodes ({@code MyClassNodes}), edit the two lists in {@link BuiltinsClasses} (follow
 * the existing pattern).
 *
 * <li>If the class has a specific layout ({@code MyClassLayout}), you will *usually*:
 * <ul>
 * <li>Create a CoreLibrary field for the factory ({@code myClassFactory}) and initialize it by calling
 * {@code MyClassLayoutImpl.INSTANCE.createMyClassShape}.
 * <li>Set the Shape for the class by setting {@link RubyClass#instanceShape}.
 * <li>The above step is unnecessary when inheriting the superclass layout.
 * <li>See examples in this file.
 * </ul>
 *
 * <li>If the class includes some Java-defined modules, perform the inclusion by calling
 * `myClassClass.fields.include(context, node, myModule)` inside the `CoreLibrary#includeModules` method. (This can
 * also, or additionally, be done in Ruby code. Doing it here saves a few invalidations. If done both here and in Ruby
 * code, it should be done at the top of the Ruby class.)
 * </ul>
*/
public class CoreLibrary {

    public static final SourceSection UNAVAILABLE_SOURCE_SECTION = Source
            .newBuilder(TruffleRuby.LANGUAGE_ID, "", "(unavailable)")
            .build()
            .createUnavailableSection();

    private static final String ERRNO_CONFIG_PREFIX = NativeConfiguration.PREFIX + "errno.";

    private final RubyContext context;

    public final SourceSection sourceSection;

    public final RubyClass argumentErrorClass;
    public final RubyClass arrayClass;
    public final RubyClass basicObjectClass;
    public final RubyClass bindingClass;
    public final RubyClass classClass;
    public final RubyClass complexClass;
    public final RubyClass dirClass;
    public final RubyClass encodingClass;
    public final RubyClass encodingConverterClass;
    public final RubyClass encodingErrorClass;
    public final RubyClass exceptionClass;
    public final RubyClass falseClass;
    public final RubyClass fiberClass;
    public final RubyClass floatClass;
    public final RubyClass floatDomainErrorClass;
    public final RubyClass frozenErrorClass;
    public final RubyClass hashClass;
    public final RubyClass integerClass;
    public final RubyClass indexErrorClass;
    public final RubyClass keyErrorClass;
    public final RubyClass ioErrorClass;
    public final RubyClass loadErrorClass;
    public final RubyClass localJumpErrorClass;
    public final RubyClass matchDataClass;
    public final RubyClass moduleClass;
    public final RubyClass nameErrorClass;
    public final RubyClass nilClass;
    public final RubyClass noMemoryErrorClass;
    public final RubyClass noMethodErrorClass;
    public final RubyClass notImplementedErrorClass;
    public final RubyClass numericClass;
    public final RubyClass objectClass;
    public final RubyClass procClass;
    public final RubyModule processModule;
    public final RubyClass rangeClass;
    public final RubyClass rangeErrorClass;
    public final RubyClass rationalClass;
    public final RubyClass regexpClass;
    public final RubyClass regexpErrorClass;
    public final RubyClass graalErrorClass;
    public final RubyClass runtimeErrorClass;
    public final RubyClass signalExceptionClass;
    public final RubyClass systemStackErrorClass;
    public final RubyClass securityErrorClass;
    public final RubyClass standardErrorClass;
    public final RubyModule polyglotModule;
    public final RubyClass polyglotForeignObjectClass;
    public final RubyClass unsupportedMessageErrorClass;
    public final RubyClass stringClass;
    public final RubyClass symbolClass;
    public final RubyClass syntaxErrorClass;
    public final RubyClass systemCallErrorClass;
    public final RubyClass systemExitClass;
    public final RubyClass threadClass;
    public final RubyClass threadBacktraceLocationClass;
    public final RubyClass trueClass;
    public final RubyClass typeErrorClass;
    public final RubyClass zeroDivisionErrorClass;
    public final RubyModule enumerableModule;
    public final RubyModule errnoModule;
    public final RubyModule kernelModule;
    public final RubyModule truffleFFIModule;
    public final RubyClass truffleFFIPointerClass;
    public final RubyClass truffleFFINullPointerErrorClass;
    public final RubyModule truffleTypeModule;
    public final RubyModule truffleModule;
    public final RubyModule truffleInternalModule;
    public final RubyModule truffleBootModule;
    public final RubyModule truffleExceptionOperationsModule;
    public final RubyModule truffleInteropModule;
    public final RubyClass truffleInteropForeignClass;
    public final RubyClass unsupportedMessageExceptionClass;
    public final RubyClass invalidArrayIndexExceptionClass;
    public final RubyClass unknownIdentifierExceptionClass;
    public final RubyClass unsupportedTypeExceptionClass;
    public final RubyClass arityExceptionClass;
    public final RubyModule truffleFeatureLoaderModule;
    public final RubyModule truffleKernelOperationsModule;
    public final RubyModule truffleStringOperationsModule;
    public final RubyModule truffleRegexpOperationsModule;
    public final RubyModule truffleThreadOperationsModule;
    public final RubyClass bigDecimalClass;
    public final RubyModule bigDecimalOperationsModule;
    public final RubyClass encodingCompatibilityErrorClass;
    public final RubyClass encodingUndefinedConversionErrorClass;
    public final RubyClass methodClass;
    public final RubyClass unboundMethodClass;
    public final RubyClass byteArrayClass;
    public final RubyClass fiberErrorClass;
    public final RubyClass threadErrorClass;
    public final RubyModule objectSpaceModule;
    public final RubyClass randomizerClass;
    public final RubyClass handleClass;
    public final RubyClass ioClass;
    public final RubyClass closedQueueErrorClass;
    public final RubyModule warningModule;
    public final RubyClass digestClass;
    public final RubyClass structClass;
    public final RubyClass weakMapClass;

    public final RubyArray argv;
    public final RubyBasicObject mainObject;

    public final GlobalVariables globalVariables;

    public final FrameDescriptor emptyDescriptor;
    /* Some things (such as procs created from symbols) require a declaration frame, and this should include a slot for
     * special variable storage. This frame descriptor should be used for those frames to provide a constant frame
     * descriptor in those cases. */
    public final FrameDescriptor emptyDeclarationDescriptor;
    public final FrameSlot emptyDeclarationSpecialVariableSlot;

    @CompilationFinal private RubyClass eagainWaitReadable;
    @CompilationFinal private RubyClass eagainWaitWritable;

    private final Map<String, RubyClass> errnoClasses = new HashMap<>();
    private final Map<Integer, String> errnoValueToNames = new HashMap<>();

    @CompilationFinal private SharedMethodInfo basicObjectSendInfo;
    @CompilationFinal private SharedMethodInfo kernelPublicSendInfo;
    @CompilationFinal private SharedMethodInfo truffleBootMainInfo;

    @CompilationFinal private GlobalVariableReader loadPathReader;
    @CompilationFinal private GlobalVariableReader debugReader;
    @CompilationFinal private GlobalVariableReader verboseReader;
    @CompilationFinal private GlobalVariableReader stdinReader;
    @CompilationFinal private GlobalVariableReader stderrReader;

    @CompilationFinal public RubyBinding topLevelBinding;
    @CompilationFinal public TopScopeObject topScopeObject;

    private final ConcurrentMap<String, Boolean> patchFiles;

    public final String coreLoadPath;
    public final String corePath;

    @TruffleBoundary
    private SourceSection initCoreSourceSection(RubyContext context) {
        final Source.SourceBuilder builder = Source.newBuilder(TruffleRuby.LANGUAGE_ID, "", "(core)");
        if (context.getOptions().CORE_AS_INTERNAL) {
            builder.internal(true);
        }

        final Source source;

        try {
            source = builder.build();
        } catch (IOException e) {
            throw CompilerDirectives.shouldNotReachHere(e);
        }

        return source.createUnavailableSection();
    }

    private String buildCoreLoadPath() {
        String path = context.getOptions().CORE_LOAD_PATH;

        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (path.startsWith(RubyLanguage.RESOURCE_SCHEME)) {
            return path;
        }

        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            throw CompilerDirectives.shouldNotReachHere(e);
        }
    }

    private enum State {
        INITIALIZING,
        LOADING_RUBY_CORE,
        LOADED
    }

    private State state = State.INITIALIZING;

    private final SingletonClassNode node;

    public CoreLibrary(RubyContext context) {
        this.context = context;
        this.coreLoadPath = buildCoreLoadPath();
        this.corePath = coreLoadPath + File.separator + "core" + File.separator;
        this.sourceSection = initCoreSourceSection(context);
        this.node = SingletonClassNode.getUncached();

        final RubyLanguage language = context.getLanguageSlow();

        // Nothing in this constructor can use RubyContext.getCoreLibrary() as we are building it!
        // Therefore, only initialize the core classes and modules here.

        // Create the cyclic classes and modules

        classClass = ClassNodes.createClassClass(context);

        basicObjectClass = ClassNodes
                .createBootClass(context, classClass, Nil.INSTANCE, "BasicObject", language.basicObjectShape);
        objectClass = ClassNodes
                .createBootClass(context, classClass, basicObjectClass, "Object", language.basicObjectShape);
        moduleClass = ClassNodes.createBootClass(context, classClass, objectClass, "Module", language.moduleShape);

        // Close the cycles
        // Set superclass of Class to Module
        classClass.setSuperClass(moduleClass);

        // Set constants in Object and lexical parents
        classClass.fields.getAdoptedByLexicalParent(context, objectClass, "Class", node);
        basicObjectClass.fields.getAdoptedByLexicalParent(context, objectClass, "BasicObject", node);
        objectClass.fields.getAdoptedByLexicalParent(context, objectClass, "Object", node);
        moduleClass.fields.getAdoptedByLexicalParent(context, objectClass, "Module", node);

        // Create Exception classes

        // Exception
        exceptionClass = defineClass("Exception", RubyLanguage.exceptionShape);

        // fatal
        defineClass(exceptionClass, "fatal");

        // NoMemoryError
        noMemoryErrorClass = defineClass(exceptionClass, "NoMemoryError");

        // StandardError
        standardErrorClass = defineClass(exceptionClass, "StandardError");
        argumentErrorClass = defineClass(standardErrorClass, "ArgumentError");
        encodingErrorClass = defineClass(standardErrorClass, "EncodingError");
        fiberErrorClass = defineClass(standardErrorClass, "FiberError");
        ioErrorClass = defineClass(standardErrorClass, "IOError");
        localJumpErrorClass = defineClass(standardErrorClass, "LocalJumpError");
        regexpErrorClass = defineClass(standardErrorClass, "RegexpError");
        threadErrorClass = defineClass(standardErrorClass, "ThreadError");
        typeErrorClass = defineClass(standardErrorClass, "TypeError");
        zeroDivisionErrorClass = defineClass(standardErrorClass, "ZeroDivisionError");
        polyglotModule = defineModule("Polyglot");
        polyglotForeignObjectClass = defineClass(polyglotModule, objectClass, "ForeignObject");
        unsupportedMessageErrorClass = defineClass(polyglotModule, standardErrorClass, "UnsupportedMessageError");
        polyglotForeignObjectClass = defineClass(polyglotModule, objectClass, "ForeignObject");

        // StandardError > RuntimeError
        runtimeErrorClass = defineClass(standardErrorClass, "RuntimeError");
        frozenErrorClass = defineClass(runtimeErrorClass, "FrozenError");

        // StandardError > RangeError
        rangeErrorClass = defineClass(standardErrorClass, "RangeError");
        floatDomainErrorClass = defineClass(rangeErrorClass, "FloatDomainError");

        // StandardError > IndexError
        indexErrorClass = defineClass(standardErrorClass, "IndexError");
        keyErrorClass = defineClass(indexErrorClass, "KeyError");
        RubyClass stopIterationClass = defineClass(indexErrorClass, "StopIteration");
        closedQueueErrorClass = defineClass(stopIterationClass, "ClosedQueueError");

        // StandardError > IOError
        defineClass(ioErrorClass, "EOFError");

        // StandardError > NameError
        nameErrorClass = defineClass(standardErrorClass, "NameError", RubyLanguage.nameErrorShape);
        noMethodErrorClass = defineClass(nameErrorClass, "NoMethodError", RubyLanguage.noMethodErrorShape);

        // StandardError > SystemCallError
        systemCallErrorClass = defineClass(standardErrorClass, "SystemCallError", RubyLanguage.systemCallErrorShape);

        errnoModule = defineModule("Errno");

        // ScriptError
        RubyClass scriptErrorClass = defineClass(exceptionClass, "ScriptError");
        loadErrorClass = defineClass(scriptErrorClass, "LoadError");
        notImplementedErrorClass = defineClass(scriptErrorClass, "NotImplementedError");
        syntaxErrorClass = defineClass(scriptErrorClass, "SyntaxError");

        // SecurityError
        securityErrorClass = defineClass(exceptionClass, "SecurityError");

        // SignalException
        signalExceptionClass = defineClass(exceptionClass, "SignalException");
        defineClass(signalExceptionClass, "Interrupt");

        // SystemExit
        systemExitClass = defineClass(exceptionClass, "SystemExit");

        // SystemStackError
        systemStackErrorClass = defineClass(exceptionClass, "SystemStackError");

        // Create core classes and modules

        numericClass = defineClass("Numeric");
        complexClass = defineClass(numericClass, "Complex");
        floatClass = defineClass(numericClass, "Float");
        integerClass = defineClass(numericClass, "Integer");
        rationalClass = defineClass(numericClass, "Rational");

        // Classes defined in Object

        arrayClass = defineClass("Array", RubyLanguage.arrayShape);
        bindingClass = defineClass("Binding", RubyLanguage.bindingShape);
        defineClass("ConditionVariable", RubyLanguage.conditionVariableShape);
        defineClass("Data"); // Needed by Socket::Ifaddr and defined in core MRI
        dirClass = defineClass("Dir");
        encodingClass = defineClass("Encoding", RubyLanguage.encodingShape);
        falseClass = defineClass("FalseClass");
        fiberClass = defineClass("Fiber", RubyLanguage.fiberShape);
        defineModule("FileTest");
        hashClass = defineClass("Hash", RubyLanguage.hashShape);
        matchDataClass = defineClass("MatchData", RubyLanguage.matchDataShape);
        methodClass = defineClass("Method", RubyLanguage.methodShape);
        defineClass("Mutex", RubyLanguage.mutexShape);
        nilClass = defineClass("NilClass");
        procClass = defineClass("Proc", RubyLanguage.procShape);

        processModule = defineModule("Process");
        RubyClass queueClass = defineClass("Queue", RubyLanguage.queueShape);
        defineClass(queueClass, "SizedQueue", RubyLanguage.sizedQueueShape);
        rangeClass = defineClass("Range", RubyLanguage.objectRangeShape);

        regexpClass = defineClass("Regexp", RubyLanguage.regexpShape);
        stringClass = defineClass("String", RubyLanguage.stringShape);
        symbolClass = defineClass("Symbol");

        threadClass = defineClass("Thread", RubyLanguage.threadShape);
        DynamicObjectLibrary.getUncached().put(threadClass, "@report_on_exception", true);
        DynamicObjectLibrary.getUncached().put(threadClass, "@abort_on_exception", false);

        RubyClass threadBacktraceClass = defineClass(threadClass, objectClass, "Backtrace");
        threadBacktraceLocationClass = defineClass(
                threadBacktraceClass,
                objectClass,
                "Location",
                RubyLanguage.threadBacktraceLocationShape);
        defineClass("Time", RubyLanguage.timeShape);
        trueClass = defineClass("TrueClass");
        unboundMethodClass = defineClass("UnboundMethod", RubyLanguage.unboundMethodShape);
        ioClass = defineClass("IO", RubyLanguage.ioShape);
        defineClass(ioClass, "File");
        structClass = defineClass("Struct");

        defineClass("TracePoint", RubyLanguage.tracePointShape);

        // Modules

        RubyModule comparableModule = defineModule("Comparable");
        enumerableModule = defineModule("Enumerable");
        defineModule("GC");
        kernelModule = defineModule("Kernel");
        defineModule("Math");
        objectSpaceModule = defineModule("ObjectSpace");

        weakMapClass = defineClass(objectSpaceModule, objectClass, "WeakMap", RubyLanguage.weakMapShape);

        // The rest

        encodingCompatibilityErrorClass = defineClass(encodingClass, encodingErrorClass, "CompatibilityError");
        encodingUndefinedConversionErrorClass = defineClass(
                encodingClass,
                encodingErrorClass,
                "UndefinedConversionError");

        encodingConverterClass = defineClass(
                encodingClass,
                objectClass,
                "Converter",
                RubyLanguage.encodingConverterShape);
        final RubyModule truffleRubyModule = defineModule("TruffleRuby");
        defineClass(truffleRubyModule, objectClass, "AtomicReference", RubyLanguage.atomicReferenceShape);
        truffleModule = defineModule("Truffle");
        truffleInternalModule = defineModule(truffleModule, "Internal");
        graalErrorClass = defineClass(truffleModule, exceptionClass, "GraalError");
        truffleExceptionOperationsModule = defineModule(truffleModule, "ExceptionOperations");
        truffleInteropModule = defineModule(truffleModule, "Interop");
        truffleInteropForeignClass = defineClass(truffleInteropModule, objectClass, "Foreign");
        RubyClass interopExceptionClass = defineClass(
                truffleInteropModule,
                exceptionClass,
                "InteropException");
        unsupportedMessageExceptionClass = defineClass(
                truffleInteropModule,
                interopExceptionClass,
                "UnsupportedMessageException");
        invalidArrayIndexExceptionClass = defineClass(
                truffleInteropModule,
                interopExceptionClass,
                "InvalidArrayIndexException");
        unknownIdentifierExceptionClass = defineClass(
                truffleInteropModule,
                interopExceptionClass,
                "UnknownIdentifierException");
        unsupportedTypeExceptionClass = defineClass(
                truffleInteropModule,
                interopExceptionClass,
                "UnsupportedTypeException");
        arityExceptionClass = defineClass(
                truffleInteropModule,
                interopExceptionClass,
                "ArityException");
        defineModule(truffleModule, "CExt");
        defineModule(truffleModule, "Debug");
        defineModule(truffleModule, "Digest");
        defineModule(truffleModule, "ObjSpace");
        defineModule(truffleModule, "Coverage");
        defineModule(truffleModule, "Graal");
        defineModule(truffleModule, "Ropes");
        truffleRegexpOperationsModule = defineModule(truffleModule, "RegexpOperations");
        truffleStringOperationsModule = defineModule(truffleModule, "StringOperations");
        truffleBootModule = defineModule(truffleModule, "Boot");
        defineModule(truffleModule, "System");
        truffleFeatureLoaderModule = defineModule(truffleModule, "FeatureLoader");
        truffleKernelOperationsModule = defineModule(truffleModule, "KernelOperations");
        defineModule(truffleModule, "Binding");
        defineModule(truffleModule, "POSIX");
        defineModule(truffleModule, "Readline");
        defineModule(truffleModule, "ReadlineHistory");
        truffleThreadOperationsModule = defineModule(truffleModule, "ThreadOperations");
        defineModule(truffleModule, "WeakRefOperations");
        handleClass = defineClass(truffleModule, objectClass, "Handle", RubyLanguage.handleShape);
        warningModule = defineModule("Warning");

        bigDecimalClass = defineClass(numericClass, "BigDecimal", RubyLanguage.bigDecimalShape);
        bigDecimalOperationsModule = defineModule(truffleModule, "BigDecimalOperations");

        truffleFFIModule = defineModule(truffleModule, "FFI");
        RubyClass truffleFFIAbstractMemoryClass = defineClass(truffleFFIModule, objectClass, "AbstractMemory");
        truffleFFIPointerClass = defineClass(
                truffleFFIModule,
                truffleFFIAbstractMemoryClass,
                "Pointer",
                RubyLanguage.truffleFFIPointerShape);
        truffleFFINullPointerErrorClass = defineClass(truffleFFIModule, runtimeErrorClass, "NullPointerError");

        truffleTypeModule = defineModule(truffleModule, "Type");

        byteArrayClass = defineClass(truffleModule, objectClass, "ByteArray", RubyLanguage.byteArrayShape);
        defineClass(truffleModule, objectClass, "StringData");
        defineClass(encodingClass, objectClass, "Transcoding");
        randomizerClass = defineClass(truffleModule, objectClass, "Randomizer", RubyLanguage.randomizerShape);

        // Standard library

        digestClass = defineClass(truffleModule, basicObjectClass, "Digest", RubyLanguage.digestShape);

        // Include the core modules

        includeModules(comparableModule);

        // Create some key objects

        mainObject = new RubyBasicObject(objectClass, language.basicObjectShape);
        emptyDescriptor = new FrameDescriptor(Nil.INSTANCE);
        emptyDeclarationDescriptor = new FrameDescriptor(Nil.INSTANCE);
        emptyDeclarationSpecialVariableSlot = emptyDeclarationDescriptor
                .addFrameSlot(Layouts.SPECIAL_VARIABLES_STORAGE);
        argv = new RubyArray(arrayClass, RubyLanguage.arrayShape, ArrayStoreLibrary.INITIAL_STORE, 0);

        globalVariables = new GlobalVariables();
        topScopeObject = new TopScopeObject(
                new Object[]{ new GlobalVariablesObject(globalVariables), mainObject });

        patchFiles = initializePatching(context);
    }

    @SuppressFBWarnings("SIC")
    private ConcurrentMap<String, Boolean> initializePatching(RubyContext context) {
        defineModule(truffleModule, "Patching");
        final ConcurrentMap<String, Boolean> patchFiles = new ConcurrentHashMap<>();

        if (context.getOptions().PATCHING) {
            try {
                final Path patchesDirectory = Paths.get(context.getRubyHome(), "lib", "patches");
                Files.walkFileTree(
                        patchesDirectory,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                                String relativePath = patchesDirectory.relativize(path).toString();
                                if (relativePath.endsWith(".rb")) {
                                    patchFiles.put(relativePath.substring(0, relativePath.length() - 3), false);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException ignored) {
                // bad ruby home
            }
        }
        return patchFiles;
    }

    private void includeModules(RubyModule comparableModule) {
        objectClass.fields.include(context, node, kernelModule);

        numericClass.fields.include(context, node, comparableModule);
        symbolClass.fields.include(context, node, comparableModule);

        arrayClass.fields.include(context, node, enumerableModule);
        dirClass.fields.include(context, node, enumerableModule);
        hashClass.fields.include(context, node, enumerableModule);
        rangeClass.fields.include(context, node, enumerableModule);
        weakMapClass.fields.include(context, node, enumerableModule);
    }

    public void initialize() {
        initializeConstants();
    }

    public void loadCoreNodes() {
        final CoreMethodNodeManager coreMethodNodeManager = new CoreMethodNodeManager(context);
        coreMethodNodeManager.loadCoreMethodNodes();

        basicObjectSendInfo = getMethod(basicObjectClass, "__send__").getSharedMethodInfo();
        kernelPublicSendInfo = getMethod(kernelModule, "public_send").getSharedMethodInfo();
        truffleBootMainInfo = getMethod(node.executeSingletonClass(truffleBootModule), "main").getSharedMethodInfo();
    }

    private InternalMethod getMethod(RubyModule module, String name) {
        InternalMethod method = module.fields.getMethod(name);
        if (method == null || method.isUndefined()) {
            throw new Error("method " + module + "#" + name + " not found during CoreLibrary initialization");
        }
        return method;
    }

    private Object verbosityOption() {
        switch (context.getOptions().VERBOSITY) {
            case NIL:
                return Nil.INSTANCE;
            case FALSE:
                return false;
            case TRUE:
                return true;
            default:
                throw CompilerDirectives.shouldNotReachHere();
        }
    }

    private void findGlobalVariableStorage() {
        loadPathReader = globalVariables.getReader("$LOAD_PATH");
        debugReader = globalVariables.getReader("$DEBUG");
        verboseReader = globalVariables.getReader("$VERBOSE");
        stdinReader = globalVariables.getReader("$stdin");
        stderrReader = globalVariables.getReader("$stderr");
    }

    private void initializeConstants() {
        setConstant(truffleFFIModule, "TYPE_CHAR", NativeTypes.TYPE_CHAR);
        setConstant(truffleFFIModule, "TYPE_UCHAR", NativeTypes.TYPE_UCHAR);
        setConstant(truffleFFIModule, "TYPE_BOOL", NativeTypes.TYPE_BOOL);
        setConstant(truffleFFIModule, "TYPE_SHORT", NativeTypes.TYPE_SHORT);
        setConstant(truffleFFIModule, "TYPE_USHORT", NativeTypes.TYPE_USHORT);
        setConstant(truffleFFIModule, "TYPE_INT", NativeTypes.TYPE_INT);
        setConstant(truffleFFIModule, "TYPE_UINT", NativeTypes.TYPE_UINT);
        setConstant(truffleFFIModule, "TYPE_LONG", NativeTypes.TYPE_LONG);
        setConstant(truffleFFIModule, "TYPE_ULONG", NativeTypes.TYPE_ULONG);
        setConstant(truffleFFIModule, "TYPE_LL", NativeTypes.TYPE_LL);
        setConstant(truffleFFIModule, "TYPE_ULL", NativeTypes.TYPE_ULL);
        setConstant(truffleFFIModule, "TYPE_FLOAT", NativeTypes.TYPE_FLOAT);
        setConstant(truffleFFIModule, "TYPE_DOUBLE", NativeTypes.TYPE_DOUBLE);
        setConstant(truffleFFIModule, "TYPE_PTR", NativeTypes.TYPE_PTR);
        setConstant(truffleFFIModule, "TYPE_VOID", NativeTypes.TYPE_VOID);
        setConstant(truffleFFIModule, "TYPE_STRING", NativeTypes.TYPE_STRING);
        setConstant(truffleFFIModule, "TYPE_STRPTR", NativeTypes.TYPE_STRPTR);
        setConstant(truffleFFIModule, "TYPE_CHARARR", NativeTypes.TYPE_CHARARR);
        setConstant(truffleFFIModule, "TYPE_ENUM", NativeTypes.TYPE_ENUM);
        setConstant(truffleFFIModule, "TYPE_VARARGS", NativeTypes.TYPE_VARARGS);

        setConstant(truffleFFIPointerClass, "UNBOUNDED", Pointer.UNBOUNDED);

        setConstant(objectClass, "RUBY_VERSION", frozenUSASCIIString(TruffleRuby.LANGUAGE_VERSION));
        setConstant(objectClass, "RUBY_PATCHLEVEL", 0);
        setConstant(objectClass, "RUBY_REVISION", frozenUSASCIIString(TruffleRuby.LANGUAGE_REVISION));
        setConstant(objectClass, "RUBY_ENGINE", frozenUSASCIIString(TruffleRuby.ENGINE_ID));
        setConstant(objectClass, "RUBY_ENGINE_VERSION", frozenUSASCIIString(TruffleRuby.getEngineVersion()));
        setConstant(objectClass, "RUBY_PLATFORM", frozenUSASCIIString(RubyLanguage.PLATFORM));
        setConstant(
                objectClass,
                "RUBY_RELEASE_DATE",
                frozenUSASCIIString(BuildInformationImpl.INSTANCE.getCompileDate()));
        setConstant(
                objectClass,
                "RUBY_DESCRIPTION",
                frozenUSASCIIString(TruffleRuby.getVersionString(Truffle.getRuntime().getName())));
        setConstant(objectClass, "RUBY_COPYRIGHT", frozenUSASCIIString(TruffleRuby.RUBY_COPYRIGHT));

        // BasicObject knows itself
        setConstant(basicObjectClass, "BasicObject", basicObjectClass);

        setConstant(objectClass, "ARGV", argv);

        setConstant(truffleModule, "UNDEFINED", NotProvided.INSTANCE);

        setConstant(encodingConverterClass, "INVALID_MASK", EConvFlags.INVALID_MASK);
        setConstant(encodingConverterClass, "INVALID_REPLACE", EConvFlags.INVALID_REPLACE);
        setConstant(encodingConverterClass, "UNDEF_MASK", EConvFlags.UNDEF_MASK);
        setConstant(encodingConverterClass, "UNDEF_REPLACE", EConvFlags.UNDEF_REPLACE);
        setConstant(encodingConverterClass, "UNDEF_HEX_CHARREF", EConvFlags.UNDEF_HEX_CHARREF);
        setConstant(encodingConverterClass, "PARTIAL_INPUT", EConvFlags.PARTIAL_INPUT);
        setConstant(encodingConverterClass, "AFTER_OUTPUT", EConvFlags.AFTER_OUTPUT);
        setConstant(encodingConverterClass, "UNIVERSAL_NEWLINE_DECORATOR", EConvFlags.UNIVERSAL_NEWLINE_DECORATOR);
        setConstant(encodingConverterClass, "CRLF_NEWLINE_DECORATOR", EConvFlags.CRLF_NEWLINE_DECORATOR);
        setConstant(encodingConverterClass, "CR_NEWLINE_DECORATOR", EConvFlags.CR_NEWLINE_DECORATOR);
        setConstant(encodingConverterClass, "XML_TEXT_DECORATOR", EConvFlags.XML_TEXT_DECORATOR);
        setConstant(encodingConverterClass, "XML_ATTR_CONTENT_DECORATOR", EConvFlags.XML_ATTR_CONTENT_DECORATOR);
        setConstant(encodingConverterClass, "XML_ATTR_QUOTE_DECORATOR", EConvFlags.XML_ATTR_QUOTE_DECORATOR);

        // Errno classes and constants
        for (Entry<String, Object> entry : context.getNativeConfiguration().getSection(ERRNO_CONFIG_PREFIX)) {
            final String name = entry.getKey().substring(ERRNO_CONFIG_PREFIX.length());
            if (name.equals("EWOULDBLOCK") && getErrnoValue("EWOULDBLOCK") == getErrnoValue("EAGAIN")) {
                continue; // Don't define it as a class, define it as constant later.
            }
            errnoValueToNames.put((int) entry.getValue(), name);
            final RubyClass rubyClass = defineClass(errnoModule, systemCallErrorClass, name);
            setConstant(rubyClass, "Errno", entry.getValue());
            errnoClasses.put(name, rubyClass);
        }

        if (getErrnoValue("EWOULDBLOCK") == getErrnoValue("EAGAIN")) {
            setConstant(errnoModule, "EWOULDBLOCK", errnoClasses.get("EAGAIN"));
        }
    }

    private void setConstant(RubyModule module, String name, Object value) {
        module.fields.setConstant(context, node, name, value);
    }

    private RubyString frozenUSASCIIString(String string) {
        final Rope rope = context.getLanguageSlow().ropeCache.getRope(
                StringOperations.encodeRope(string, USASCIIEncoding.INSTANCE, CodeRange.CR_7BIT));
        return StringOperations.createFrozenString(context, rope);
    }

    private RubyClass defineClass(String name) {
        return defineClass(objectClass, name);
    }

    private RubyClass defineClass(String name, Shape instanceShape) {
        return defineClass(objectClass, name, instanceShape);
    }

    private RubyClass defineClass(RubyClass superclass, String name) {
        return defineClass(superclass, name, superclass.instanceShape);
    }

    private RubyClass defineClass(RubyClass superclass, String name, Shape instanceShape) {
        return ClassNodes.createInitializedRubyClass(context, null, objectClass, superclass, name, instanceShape);
    }

    private RubyClass defineClass(RubyModule lexicalParent, RubyClass superclass, String name) {
        return defineClass(lexicalParent, superclass, name, superclass.instanceShape);
    }

    private RubyClass defineClass(RubyModule lexicalParent, RubyClass superclass, String name, Shape instanceShape) {
        return ClassNodes.createInitializedRubyClass(context, null, lexicalParent, superclass, name, instanceShape);
    }

    private RubyModule defineModule(String name) {
        return defineModule(null, objectClass, name);
    }

    private RubyModule defineModule(RubyModule lexicalParent, String name) {
        return defineModule(null, lexicalParent, name);
    }

    private RubyModule defineModule(SourceSection sourceSection, RubyModule lexicalParent, String name) {
        return ModuleNodes.createModule(context, sourceSection, moduleClass, lexicalParent, name, node);
    }

    @SuppressFBWarnings("ES")
    public void loadRubyCoreLibraryAndPostBoot() {
        state = State.LOADING_RUBY_CORE;

        try {
            for (int n = 0; n < CORE_FILES.length; n++) {
                final String file = CORE_FILES[n];
                if (file == POST_BOOT_FILE) {
                    afterLoadCoreLibrary();
                    state = State.LOADED;
                }

                final RubySource source = loadCoreFile(coreLoadPath + file);
                final RubyRootNode rootNode = context
                        .getCodeLoader()
                        .parse(source, ParserContext.TOP_LEVEL, null, null, true, node);

                final CodeLoader.DeferredCall deferredCall = context.getCodeLoader().prepareExecute(
                        ParserContext.TOP_LEVEL,
                        DeclarationContext.topLevel(context),
                        rootNode,
                        null,
                        context.getCoreLibrary().mainObject);

                TranslatorDriver.printParseTranslateExecuteMetric("before-execute", context, source.getSource());
                deferredCall.callWithoutCallNode();
                TranslatorDriver.printParseTranslateExecuteMetric("after-execute", context, source.getSource());
            }
        } catch (IOException e) {
            throw CompilerDirectives.shouldNotReachHere(e);
        } catch (RaiseException e) {
            context.getDefaultBacktraceFormatter().printRubyExceptionOnEnvStderr(
                    "Exception while loading core library:\n",
                    e.getException());
            throw CompilerDirectives.shouldNotReachHere("couldn't load the core library", e);
        }
    }

    public RubySource loadCoreFile(String path) throws IOException {
        if (path.startsWith(RubyLanguage.RESOURCE_SCHEME)) {
            if (TruffleOptions.AOT || ParserCache.INSTANCE != null) {
                final RootParseNode rootParseNode = ParserCache.INSTANCE.get(path);
                return new RubySource(rootParseNode.getSource(), path);
            } else {
                final ResourceLoader resourceLoader = new ResourceLoader();
                return resourceLoader.loadResource(path, context.getOptions().CORE_AS_INTERNAL);
            }
        } else {
            final FileLoader fileLoader = new FileLoader(context);
            return fileLoader.loadFile(context.getEnv(), path);
        }
    }

    private void afterLoadCoreLibrary() {
        // Get some references to things defined in the Ruby core

        eagainWaitReadable = (RubyClass) ioClass.fields
                .getConstant("EAGAINWaitReadable")
                .getValue();

        eagainWaitWritable = (RubyClass) ioClass.fields
                .getConstant("EAGAINWaitWritable")
                .getValue();

        findGlobalVariableStorage();

        // Initialize $0 so it is set to a String as RubyGems expect, also when not run from the RubyLauncher
        RubyString dollarZeroValue = StringOperations
                .createString(context, StringOperations.encodeRope("-", USASCIIEncoding.INSTANCE, CodeRange.CR_7BIT));
        globalVariables.getStorage("$0").setValueInternal(dollarZeroValue);

        topLevelBinding = (RubyBinding) objectClass.fields
                .getConstant("TOPLEVEL_BINDING")
                .getValue();
    }

    @TruffleBoundary
    public RubyClass getMetaClass(Object object) {
        if (object instanceof RubyDynamicObject) {
            return ((RubyDynamicObject) object).getMetaClass();
        } else {
            return getLogicalClass(object);
        }
    }

    @TruffleBoundary
    public RubyClass getLogicalClass(Object object) {
        if (object instanceof RubyDynamicObject) {
            return ((RubyDynamicObject) object).getLogicalClass();
        } else if (object instanceof Nil) {
            return nilClass;
        } else if (object instanceof RubyBignum) {
            return integerClass;
        } else if (object instanceof RubySymbol) {
            return symbolClass;
        } else if (object instanceof Boolean) {
            return (boolean) object ? trueClass : falseClass;
        } else if (object instanceof Byte) {
            return integerClass;
        } else if (object instanceof Short) {
            return integerClass;
        } else if (object instanceof Integer) {
            return integerClass;
        } else if (object instanceof Long) {
            return integerClass;
        } else if (object instanceof Float) {
            return floatClass;
        } else if (object instanceof Double) {
            return floatClass;
        } else {
            assert RubyGuards.isForeignObject(object);
            // return truffleInteropForeignClass;
            return polyglotForeignObjectClass;
        }
    }

    /** Convert a value to a {@code Float}, without doing any lookup. */
    public static double toDouble(Object value, Object nil) {
        assert value != null;

        if (value == nil) {
            return 0;
        }

        if (value instanceof Integer) {
            return (int) value;
        }

        if (value instanceof Long) {
            return (long) value;
        }

        if (value instanceof RubyBignum) {
            return BigIntegerOps.doubleValue((RubyBignum) value);
        }

        if (value instanceof Double) {
            return (double) value;
        }

        throw CompilerDirectives.shouldNotReachHere();
    }

    public static boolean fitsIntoInteger(long value) {
        return ((int) value) == value;
    }

    public static boolean fitsIntoUnsignedInteger(long value) {
        return value == (value & 0xffffffffL) || value < 0 && value >= Integer.MIN_VALUE;
    }

    public RubyArray getLoadPath() {
        return (RubyArray) loadPathReader.getValue(globalVariables);
    }

    public Object getDebug() {
        if (debugReader != null) {
            return debugReader.getValue(globalVariables);
        } else {
            return context.getOptions().DEBUG;
        }
    }

    private Object verbosity() {
        if (verboseReader != null) {
            return verboseReader.getValue(globalVariables);
        } else {
            return verbosityOption();
        }
    }

    /** true if $VERBOSE is true or false, but not nil */
    public boolean warningsEnabled() {
        return verbosity() != Nil.INSTANCE;
    }

    /** true only if $VERBOSE is true */
    public boolean isVerbose() {
        return verbosity() == Boolean.TRUE;
    }

    public Object getStdin() {
        return stdinReader.getValue(globalVariables);
    }

    public Object getStderr() {
        return stderrReader.getValue(globalVariables);
    }

    public RubyBasicObject getENV() {
        return (RubyBasicObject) objectClass.fields.getConstant("ENV").getValue();
    }

    @TruffleBoundary
    public int getErrnoValue(String errnoName) {
        return (int) context.getNativeConfiguration().get(ERRNO_CONFIG_PREFIX + errnoName);
    }

    @TruffleBoundary
    public String getErrnoName(int errnoValue) {
        return errnoValueToNames.get(errnoValue);
    }

    @TruffleBoundary
    public RubyClass getErrnoClass(String name) {
        return errnoClasses.get(name);
    }

    public ConcurrentMap<String, Boolean> getPatchFiles() {
        return patchFiles;
    }

    public boolean isInitializing() {
        return state == State.INITIALIZING;
    }

    public boolean isLoadingRubyCore() {
        return state == State.LOADING_RUBY_CORE;
    }

    public boolean isLoaded() {
        return state == State.LOADED;
    }

    public boolean isSend(InternalMethod method) {
        return isSend(method.getSharedMethodInfo());
    }

    public boolean isSend(SharedMethodInfo sharedMethodInfo) {
        return sharedMethodInfo == basicObjectSendInfo || sharedMethodInfo == kernelPublicSendInfo;
    }

    public boolean isTruffleBootMainMethod(SharedMethodInfo info) {
        return info == truffleBootMainInfo;
    }

    private static final String POST_BOOT_FILE = "/post-boot/post-boot.rb";

    public static final String[] CORE_FILES = {
            "/core/pre.rb",
            "/core/basic_object.rb",
            "/core/array.rb",
            "/core/channel.rb",
            "/core/configuration.rb",
            "/core/false.rb",
            "/core/gc.rb",
            "/core/nil.rb",
            "/core/truffle/platform.rb",
            "/core/string.rb",
            "/core/random.rb",
            "/core/truffle/kernel_operations.rb",
            "/core/truffle/exception_operations.rb",
            "/core/truffle/feature_loader.rb",
            "/core/truffle/gem_util.rb",
            "/core/truffle/thread_operations.rb",
            "/core/thread.rb",
            "/core/true.rb",
            "/core/type.rb",
            "/core/truffle/ffi/pointer.rb",
            "/core/truffle/ffi/pointer_access.rb",
            "/core/truffle/io_operations.rb",
            "/core/truffle/internal.rb",
            "/core/kernel.rb",
            "/core/lazy_rubygems.rb",
            "/core/truffle/boot.rb",
            "/core/truffle/debug.rb",
            "/core/truffle/encoding_operations.rb",
            "/core/truffle/hash_operations.rb",
            "/core/truffle/numeric_operations.rb",
            "/core/truffle/proc_operations.rb",
            "/core/truffle/range_operations.rb",
            "/core/truffle/regexp_operations.rb",
            "/core/truffle/stat_operations.rb",
            "/core/truffle/string_operations.rb",
            "/core/truffle/backward.rb",
            "/core/truffle/truffleruby.rb",
            "/core/splitter.rb",
            "/core/stat.rb",
            "/core/io.rb",
            "/core/immediate.rb",
            "/core/module.rb",
            "/core/proc.rb",
            "/core/enumerable_helper.rb",
            "/core/enumerable.rb",
            "/core/enumerator.rb",
            "/core/argf.rb",
            "/core/exception.rb",
            "/core/hash.rb",
            "/core/comparable.rb",
            "/core/numeric.rb",
            "/core/truffle/ctype.rb",
            "/core/integer.rb",
            "/core/regexp.rb",
            "/core/transcoding.rb",
            "/core/encoding.rb",
            "/core/env.rb",
            "/core/errno.rb",
            "/core/truffle/file_operations.rb",
            "/core/file.rb",
            "/core/dir.rb",
            "/core/dir_glob.rb",
            "/core/file_test.rb",
            "/core/float.rb",
            "/core/marshal.rb",
            "/core/object_space.rb",
            "/core/range.rb",
            "/core/struct.rb",
            "/core/tms.rb",
            "/core/process.rb",
            "/core/truffle/process_operations.rb", // Must load after /core/regexp.rb
            "/core/signal.rb",
            "/core/symbol.rb",
            "/core/mutex.rb",
            "/core/throw_catch.rb",
            "/core/time.rb",
            "/core/rational.rb",
            "/core/rationalizer.rb",
            "/core/complex.rb",
            "/core/complexifier.rb",
            "/core/class.rb",
            "/core/binding.rb",
            "/core/math.rb",
            "/core/truffle/method_operations.rb",
            "/core/method.rb",
            "/core/unbound_method.rb",
            "/core/warning.rb",
            "/core/weakmap.rb",
            "/core/tracepoint.rb",
            "/core/truffle/interop.rb",
            "/core/truffle/polyglot.rb",
            "/core/posix.rb",
            "/core/main.rb",
            "/core/post.rb",
            POST_BOOT_FILE
    };

}
