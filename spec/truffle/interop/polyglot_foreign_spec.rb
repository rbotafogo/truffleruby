# Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

require_relative '../../ruby/spec_helper'

describe Polyglot do

  describe "PolyglotForeign Object" do

  it "should be able to create the ForeignClass" do
    foreign = Polyglot::ForeignObject.new
    foreign.class.should == Polyglot::ForeignObject
    foreign.hello.should == "hello from polyglot"
  end

=begin
  it "should blow" do
    foreign = Polyglot::ForeignObject.new
    foreign.blow.should == 1
  end
=end
=begin
    it "should get class" do
      foreign = Truffle::Interop.to_java_array([1, 2, 3])
      # puts foreign
      foreign[0].should == 1
    end

    it "should allow the creation of a Foreign object" do
      foreign = Truffle::Interop::Foreign.new
      puts foreign
      -> {foreign.hello}.should == "hello"
    end
=end

  end
end