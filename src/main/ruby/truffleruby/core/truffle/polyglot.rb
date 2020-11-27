# frozen_string_literal: true

# Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

Polyglot::MAIN = self

module Polyglot

  # stub defined in CoreLibrary
  class UnsupportedMessageError < StandardError
  end

  def self.export(name, value)
    Truffle::Interop.export name, value
  end

  def self.export_method(name)
    Truffle::Interop.export_method name
  end

  def self.import(name)
    Truffle::Interop.import(name)
  end

  def self.import_method(name)
    Truffle::Interop.import_method name
  end

  def self.as_enumerable(object)
    Truffle::Interop.enumerable(object)
  end

  def self.foreign_object
    Polyglot::ForeignObject.new
  end

  module HasArrayElementsTrait

    def size
      Truffle::Interop.array_size(self)
    end

    alias_method :length, :size
    alias_method :count, :size

    def read_array_element(index)
      Truffle::Interop.read_array_element(self, index)
    end

    def at(index)
      index += length if index < 0
      return nil if (index < 0 || index >= length)
      read_array_element(index)
    end

    def first
      read_array_element(0)
    end

    def last
      read_array_element(length - 1)
    end

    def each(*args)
      return enum_for(:each) unless block_given?
      i = 0
      while i < length
        yield read_array_element(i)
        i += 1
      end
    end

    def each_with_index
      return enum_for(:each) unless block_given?
      i = 0
      while i < length
        yield read_array_element(i), i
        i += 1
      end
    end

  end

  class ForeignObject
    include HasArrayElementsTrait
    include Enumerable

    def has_array_elements?
      Truffle::Interop.has_array_elements?(self)
    end

    def read_member(name)
      Truffle::Interop.member(self, name)
    end

    def get_members(include_internal = false)
      Truffle::Interop.members(self, include_internal)
    end

    def inspect
      Truffle::Interop.foreign_inspect(self)
    end

    def []=(member, value)
      Truffle::Interop.write_member(self, member, value)
    end

    def method_missing(method, *args, &block)
    end

=begin
    # TODO: This method is incomplete and only works in one
    # specific case.
    def method_missing(method, *args, &block)
      case method
      # when missing method has an '=' sign in it...
      when ->(x) { x =~ /(.*)=$/ }
        if args[0].is_a? Symbol
          Truffle::Interop.write_member(self, $1, args[0])
        else
          raise ArgumentError("Illegal number of arguments for #{method}")
        end
      else
        Truffle::Interop.read_member(self, method).to_sym
      end
    end
=end

  end
end

module Java
  def self.type(name)
    Truffle::Interop.java_type(name)
  end

  def self.import(name)
    name = name.to_s
    simple_name = name.split('.').last
    type = Java.type(name)
    if Object.const_defined?(simple_name)
      current = Object.const_get(simple_name)
      if current.equal?(type)
        # Ignore - it's already set
      else
        raise NameError, "constant #{simple_name} already set"
      end
    else
      Object.const_set simple_name, type
    end
    type
  end

  def self.synchronized(object)
    Truffle::System.synchronized(object) do
      yield
    end
  end

  # test-unit expects `Java::JavaLang::Throwable` to be resolvable if `::Java` is defined (see assertions.rb).
  # When doing JRuby-style interop, that's a fine assumption. However, we have `::Java` defined for Truffle-style
  # interop and in that case, the assumption does not hold. In order to allow the gem to work properly, we define
  # a dummy `Throwable` class here.
  module JavaLang
    class Throwable; end
  end
end
