cmake_minimum_required(VERSION 3.22.1)

project(nakourterm C)

add_library(nakourterm SHARED terminal_jni.c)

find_library(log-lib log)
target_link_libraries(nakourterm ${log-lib})
