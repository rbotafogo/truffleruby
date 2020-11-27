# Copyright (c) 2017, 2019 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

require_relative '../../ruby/spec_helper'

describe "Foreign arrays" do
  it "can be printed with #print" do
    foreign = Truffle::Interop.to_java_array([1, 2, 3])
    -> {
      print foreign
    }.should output_to_fd(foreign.to_s)
  end

  it "can be printed with #puts" do
    -> {
      puts Truffle::Interop.to_java_array([1, 2, 3])
    }.should output_to_fd("1\n2\n3\n")
  end

  it "can be printed with #p" do
    foreign = Truffle::Interop.to_java_array([1, 2, 3])
    -> {
      p foreign
    }.should output_to_fd("#{foreign.inspect}\n")
  end
end

describe "Foreign arrays that are also pointers" do
  it "can be printed with #print" do
    foreign = Truffle::Debug.foreign_pointer_array_from_java(Truffle::Interop.to_java_array([1, 2, 3]))
    -> {
      print foreign
    }.should output_to_fd(foreign.to_s)
  end

  it "can be printed with #puts" do
    -> {
      puts Truffle::Debug.foreign_pointer_array_from_java(Truffle::Interop.to_java_array([1, 2, 3]))
    }.should output_to_fd("1\n2\n3\n")
  end

  it "can be printed with #p" do
    foreign = Truffle::Debug.foreign_pointer_array_from_java(Truffle::Interop.to_java_array([1, 2, 3]))
    -> {
      p foreign
    }.should output_to_fd("#{foreign.inspect}\n")
  end
end


describe "ForeignArray as Ruby Class" do
  before do
    @foreign = Polyglot::ForeignArray.new(1, 2, 3)
    @foreign_db = Polyglot::ForeignArray.new(1.4, 2.8, 3.6)
  end

  it "should have class Polyglot::ForeignArray" do
    @foreign.class.should == Polyglot::ForeignArray
  end
  
  it "can create a foreign array" do
    @foreign[0].should == 1
    @foreign_db[0].should == 1.4
    @foreign_db[2].should == 3.6
  end

  it "can loop through array elements with each" do
    @foreign.each_index do |val, index|
      @foreign[index].should == index + 1
    end 
  end

  it "can use #take" do
    slice = @foreign.take(2)
    slice[0].should == 1
    slice[1].should == 2
  end

  it "can receive an enumerator from a foreign array with #each with no block given" do
    enum = @foreign.each
    enum.next.should == 1
    enum.next.should == 2
    enum.next.should == 3
  end
  
end
