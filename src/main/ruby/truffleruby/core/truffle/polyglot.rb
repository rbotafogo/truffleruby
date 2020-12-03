# frozen_string_literal: true

# Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

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

  # HasArrayElementsTrait
  module HasArrayElementsTrait

    def size
      Truffle::Interop.array_size(self)
    end

    alias_method :length, :size

    def count(item = nil)
      seq = 0
      if (!item.nil?)
        each { |o| seq += 1 if item == o }
      elsif block_given?
        each { |o| seq += 1 if yield(o) }
      else
        return size
      end
      seq
    end

    def toArray
      Truffle::Interop.invoke(self, :toArray)
    end

    def to_a
      if Truffle::Interop.has_array_elements?(self)
        Truffle::Interop.to_array(self)
      else
        raise RuntimeError.new
      end
    end

    alias_method :to_ary, :to_a

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

  # ForeignObject class
  class ForeignObject
    # TODO: includes should be done dynamically depending on the type of the
    # foreign object. Not sure yet how to do it
    include Enumerable
    include HasArrayElementsTrait

    def foreign_class
      Truffle::Interop.foreign_class(self)
    end

    alias_method :class, :foreign_class
    alias_method :getClass, :foreign_class

    def is_a?(klass)
      Truffle::Interop.foreign_is_a?(self, klass)
    end

    alias_method :kind_of?, :is_a?

    def nil?
      Truffle::Interop.null?(self)
    end

    def to_str
      Truffle::Interop.foreign_to_str(self)
    end

    def to_s
      Truffle::Interop.foreign_to_s(self)
    end

    def name
      Truffle::Interop.invoke(self, :getName)
    end

    def intValue
      Truffle::Interop.invoke(self, :intValue)
    end

    def longValue
      Truffle::Interop.invoke(self, :longValue)
    end

    alias_method :getName, :name

    def as_int
      Truffle::Interop.as_int(self)
    end

    def as_long
      Truffle::Interop.as_long(self)
    end

    def as_double
      Truffle::Interop.as_double(self)
    end

    alias_method :doubleValue, :as_double

    def to_i
      return as_int if Truffle::Interop.fits_in_int?(self)
      return as_long if Truffle::Interop.fits_in_long?(self)
      raise TypeError.new("can't convert foreign object to Integer")
    end

    def to_f
      return as_double if Truffle::Interop.fits_in_double?(self)
      return as_long if Truffle::Interop.fits_in_long?(self)
      raise TypeError.new("can't convert foreign object to Integer")
    end

    def unbox
      Truffle::Interop.unbox(self)
    end

    def has_array_elements?
      Truffle::Interop.has_array_elements?(self)
    end

    def get_members(include_internal = false)
      Truffle::Interop.members(self, include_internal)
    end

    alias_method :members, :get_members
    alias_method :keys, :get_members

    def inspect
      Truffle::Interop.foreign_inspect(self)
    end

    def java_class?(klass)
      Truffle::Interop.java_class?(klass)
    end

    def []=(member, value)
      return Truffle::Interop.write_array_element(self, member, value) if member.is_a? Numeric
      Truffle::Interop.write_member(self, member, value)
    end

    def delete(index)
      Truffle::Interop.remove_array_element(self, index) if index.is_a? Numeric
      Truffle::Interop.remove_member(self, index)
    end

    def +(other_object)
      Truffle::Interop.unbox_if_needed(self) + Truffle::Interop.unbox_if_needed(other_object)
    end

    # TODO: This method is incomplete and only works in one
    # specific case.
    def method_missing(method, *args, &block)
      args << block if block_given?

      case method
      # when missing method has an '=' sign in it...
      when ->(x) { x =~ /(.*)=$/ }
        Truffle::Interop.write_member(self, $1, *args)
      else
        if (Truffle::Interop.is_member_invocable?(self, method))
          Truffle::Interop.invoke(self, method, *args)
        else
          if (args.size > 0)
            # Since it is not invocable, then there is no reason to invoke the method with the block_given
            # arguments, but special_forms_spec at line 173 specifies that the call should be made.  Seems
            # like a wrong spec and probably the spec should be changed
            begin
              Truffle::Interop.invoke(self, method, *args)
            rescue
              raise NoMethodError.new
            end
          else
            Truffle::Interop.read_member(self, method)
          end
        end
      end
    end

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
