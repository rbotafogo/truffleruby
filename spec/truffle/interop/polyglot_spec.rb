# Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

require_relative '../../ruby/spec_helper'
require_relative 'fixtures/classes.rb'

describe Polyglot do

  describe "Access to ForeignObject" do
    proxy = -> obj {
      logger = TruffleInteropSpecs::Logger.new
      return Truffle::Interop.proxy_foreign_object(obj, logger), obj, logger
    }

    it "class ForeignObject should be created" do
      foreign = Truffle::Interop.to_java_array([1, 2, 3])
      # foreign[0].should == 1
      foreign.hello.should == "hello from polyglot"
    end

    it "should index array with 'at'" do
       foreign = Truffle::Interop.to_java_array([1, 2, 3])
       foreign.at(0).should == 1
    end

    it "should access Kernel methods" do
       foreign = Truffle::Interop.to_java_array([1, 2, 3])
       foreign.to_s.should == "#<Java [1, 2, 3]>"
    end

    it "returns true for a directly matching Java object and interface" do
      skip
      big_integer_class = Truffle::Interop.java_type("java.math.BigInteger")
      big_integer = big_integer_class.new("14")
      puts big_integer
      # big_integer.should == 14
      # serializable_interface = Truffle::Interop.java_type("java.io.Serializable")
      # big_integer.is_a?(serializable_interface).should be_true
    end

    it "should do something right" do
      pfo, pm, l = proxy[TruffleInteropSpecs::PolyglotMember.new]
      puts pfo
      puts pm
      puts l
      pfo.foo = :bar
      pfo.foo.should == :bar
    end


    it "should be able to send polyglot messages to foreign" do
      skip
      foreign = Truffle::Interop.to_java_array([1, 2, 3])
      foreign.polyglot_has_array_elements?.should == true
    end

  end

  describe ".eval(id, code)" do

    it "evals code in Ruby" do
      Polyglot.eval("ruby", "14 + 2").should == 16
    end

    it "raises an ArgumentError for unknown languages" do
      -> {
        Polyglot.eval("unknown-language", "code")
      }.should raise_error(ArgumentError, /No language for id unknown-language found/)
    end

    it "doesn't work with MIME types" do
      -> {
        Polyglot.eval("application/x-ruby", "14 + 2")
      }.should raise_error(ArgumentError, /No language for id application\/x-ruby found/)
    end

    it "evals code in Ruby as UTF-8" do
      Polyglot.eval("ruby", "__ENCODING__.name").should == "UTF-8"
    end

    it "evals code in Ruby as UTF-8 even if it was a different encoding in the past" do
      Polyglot.eval("ruby", "__ENCODING__.name".encode("big5")).should == "UTF-8"
    end

    it "will allow code in Ruby to have a magic comment that does not change the encoding" do
      Polyglot.eval("ruby", "# encoding: utf-8\n__ENCODING__.name").should == "UTF-8"
    end

    it "will allow code in Ruby to have a magic comment that is a subset of UTF-8" do
      Polyglot.eval("ruby", "# encoding: us-ascii\n__ENCODING__.name").should == "US-ASCII"
    end

    it "will not allow code in Ruby to have a magic comment to change the encoding to something not a subset of UTF-8" do
      -> {
        Polyglot.eval("ruby", "# encoding: big5\n__ENCODING__.name")
      }.should raise_error(ArgumentError, /big5 cannot be used as an encoding for a Polyglot API Source/)
    end

  end

  describe ".eval_file(id, path)" do

    it "evals code in Ruby" do
      Polyglot.eval_file("ruby", fixture(__FILE__, "eval_file_id.rb"))
      $eval_file_id.should be_true
    end

    it "returns the returned result of the eval" do
      Polyglot.eval_file("ruby", fixture(__FILE__, "eval_file_return.rb")).should == 14
    end

    it "returns the implicit result of the eval" do
      Polyglot.eval_file("ruby", fixture(__FILE__, "eval_file_value.rb")).should == 14
    end

    it "raises an ArgumentError for unknown languages" do
      -> {
        Polyglot.eval_file("unknown-language", fixture(__FILE__, "eval_file_id.rb"))
      }.should raise_error(ArgumentError, /No language for id unknown-language found/)
    end

    it "doesn't work with MIME types" do
      -> {
        Polyglot.eval_file("application/x-ruby", fixture(__FILE__, "eval_file_id.rb"))
      }.should raise_error(ArgumentError, /No language for id application\/x-ruby found/)
    end

    it "evals code in Ruby as UTF-8" do
      Polyglot.eval_file("ruby", fixture(__FILE__, "no_magic.rb")).should == "UTF-8"
    end

    it "will allow code in Ruby to have a magic comment that does not change the encoding" do
      Polyglot.eval_file("ruby", fixture(__FILE__, "utf8_magic.rb")).should == "UTF-8"
    end

    it "will allow code in Ruby to have a magic comment that is a subset of UTF-8" do
      Polyglot.eval_file("ruby", fixture(__FILE__, "usascii_magic.rb")).should == "US-ASCII"
    end

    it "will not allow code in Ruby to have a magic comment to change the encoding" do
      -> {
        Polyglot.eval_file("ruby", fixture(__FILE__, "big5_magic.rb"))
      }.should raise_error(ArgumentError, /big5 cannot be used as an encoding for a Polyglot API Source/)
    end

  end

  describe ".eval_file(path)" do

    it "evals code in Ruby" do
      Polyglot.eval_file(fixture(__FILE__, "eval_file.rb"))
      $eval_file.should be_true
    end

    it "raises an ArgumentError if the language is not found" do
      path = fixture(__FILE__, "eval_file.invalid")
      -> {
        Polyglot.eval_file(path)
      }.should raise_error(ArgumentError, "Could not find language of file #{path}")
    end

  end

  describe ".as_enumerable" do

    it "evals code in Ruby" do
      enumerable = -> { Polyglot.as_enumerable([1, 2, 3]) }
      enumerable.call.min.should == 1
      enumerable.call.max.should == 3
    end

  end

end
