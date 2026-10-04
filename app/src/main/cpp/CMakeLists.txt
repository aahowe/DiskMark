cmake_minimum_required(VERSION 3.22.1)
project(diskbench C)

add_library(diskbench SHARED diskbench.c)
target_compile_options(diskbench PRIVATE -O2 -Wall)
target_link_libraries(diskbench log)
