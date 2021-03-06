require 'mkmf'

# Compile a real native library
# Commands from src/main/c/truffleposix/Makefile

so = RbConfig::CONFIG['SOEXT']
cc = ENV['CC'] || 'cc'

dir = File.expand_path('../..', __FILE__)
name = "#{dir}/libnativetest"

cflags = %w[-Wall -Werror -fPIC -std=c99]
ldflags = %w[-m64]

def command(*args)
  $stderr.puts args.join(' ')
  ret = system(*args)
  raise unless ret
end

command cc, '-o', "#{name}.o", '-c', *cflags, *ldflags, "#{name}.c"
command cc, '-shared', *ldflags, '-o', "#{name}.#{so}", "#{name}.o"

$LIBS += " #{name}.#{so}"

create_makefile('backtraces')
