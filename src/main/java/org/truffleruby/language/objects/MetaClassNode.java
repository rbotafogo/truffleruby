/*
 * Copyright (c) 2015, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.objects;

import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.core.numeric.RubyBignum;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.language.Nil;
import org.truffleruby.language.RubyBaseNode;
import org.truffleruby.language.RubyDynamicObject;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;

@GenerateUncached
public abstract class MetaClassNode extends RubyBaseNode {

    public static MetaClassNode create() {
        return MetaClassNodeGen.create();
    }

    public abstract RubyClass execute(Object value);

    // Cover all primitives, nil and symbols

    @Specialization(guards = "value")
    protected RubyClass metaClassTrue(boolean value,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return context.getCoreLibrary().trueClass;
    }

    @Specialization(guards = "!value")
    protected RubyClass metaClassFalse(boolean value,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return context.getCoreLibrary().falseClass;
    }

    @Specialization
    protected RubyClass metaClassInt(int value,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return context.getCoreLibrary().integerClass;
    }

    @Specialization
    protected RubyClass metaClassLong(long value,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return context.getCoreLibrary().integerClass;
    }

    @Specialization
    protected RubyClass metaClassBignum(RubyBignum value,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return context.getCoreLibrary().integerClass;
    }

    @Specialization
    protected RubyClass metaClassDouble(double value,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return context.getCoreLibrary().floatClass;
    }

    @Specialization
    protected RubyClass metaClassNil(Nil value,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return context.getCoreLibrary().nilClass;
    }

    @Specialization
    protected RubyClass metaClassSymbol(RubySymbol value,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return context.getCoreLibrary().symbolClass;
    }

    // Cover all RubyDynamicObject cases with cached and uncached

    @Specialization(
            guards = { "object == cachedObject", "metaClass.isSingleton" },
            limit = "getIdentityCacheLimit()")
    protected RubyClass singletonClassCached(RubyDynamicObject object,
            @Cached("object") RubyDynamicObject cachedObject,
            @Cached("object.getMetaClass()") RubyClass metaClass) {
        return metaClass;
    }

    @Specialization(replaces = "singletonClassCached")
    protected RubyClass metaClassObject(RubyDynamicObject object) {
        return object.getMetaClass();
    }

    // Foreign object

    @Specialization(guards = "isForeignObject(object)")
    protected RubyClass metaClassForeign(Object object,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return context.getCoreLibrary().truffleInteropForeignClass;
        // return context.getCoreLibrary().polyglotForeignObjectClass;
    }

    protected int getCacheLimit() {
        return RubyLanguage.getCurrentContext().getOptions().CLASS_CACHE;
    }

    protected int getIdentityCacheLimit() {
        return RubyLanguage.getCurrentContext().getOptions().IDENTITY_CACHE;
    }
}
