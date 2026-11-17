cmake_minimum_required(VERSION 3.22.1)
project("aura_usb_audio" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(aura_usb_audio SHARED
    usb-audio-output.cpp
)

find_library(log-lib log)

target_link_libraries(aura_usb_audio
    ${log-lib}
)
