source "https://rubygems.org"

gem "fastlane"

# Ruby 4.0 dropped fiddle from the default gems. fastlane's tty-screen still
# requires it to measure the terminal, and without it every table it prints --
# including the one on the error path -- dies with
# "uninitialized constant TTY::Screen::Fiddle", masking the real failure.
gem "fiddle"
