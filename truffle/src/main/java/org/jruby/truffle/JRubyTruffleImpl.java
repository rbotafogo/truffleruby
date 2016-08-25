/*
 * Copyright (c) 2014, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle;

import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.vm.PolyglotEngine;
import org.jruby.JRubyTruffleInterface;
import org.jruby.Ruby;
import org.jruby.truffle.interop.JRubyContextWrapper;
import org.jruby.truffle.language.control.ExitException;
import org.jruby.truffle.platform.graal.Graal;
import org.jruby.util.cli.Options;

public class JRubyTruffleImpl implements JRubyTruffleInterface {

    private final PolyglotEngine engine;
    private final RubyContext context;

    // Created by reflection from Ruby#loadTruffle

    public JRubyTruffleImpl(Ruby runtime) {
        engine = PolyglotEngine.newBuilder()
                .globalSymbol(JRubyTruffleInterface.RUNTIME_SYMBOL, new JRubyContextWrapper(runtime))
                .build();
        context = (RubyContext) engine.eval(loadSource("Truffle::Boot.context", "context")).get();
    }

    @Override
    public Object execute(org.jruby.ast.RootNode rootNode) {
        if (!Graal.isGraal() && Options.TRUFFLE_GRAAL_WARNING_UNLESS.load()) {
            System.err.println("WARNING: This JVM does not have the Graal compiler. " +
                    "JRuby+Truffle's performance without it will be limited. " +
                    "See https://github.com/jruby/jruby/wiki/Truffle-FAQ#how-do-i-get-jrubytruffle");
        }

        context.getJRubyInterop().setOriginalInputFile(rootNode.getPosition().getFile());

        try {
            return engine.eval(loadSource("Truffle::Boot.run_jruby_root", "run_jruby_root")).get();
        } catch (ExitException e) {
            throw new org.jruby.exceptions.MainExitException(e.getCode());
        }
    }

    @Override
    public void dispose() {
        engine.dispose();
    }

    private Source loadSource(String source, String name) {
        return Source.newBuilder(source).name(name).mimeType(RubyLanguage.MIME_TYPE).build();
    }
    
}
