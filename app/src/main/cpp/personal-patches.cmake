# Adaptations live in scripts/patches; CMake only connects generated sources.
find_package(Python3 REQUIRED COMPONENTS Interpreter)
set(PERSONAL_PATCH_TOOL "${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/apply_personal_patches.py")
file(GLOB PERSONAL_PATCH_MODULES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/../../../../scripts/patches/*.py")

function(personal_patch_target target component)
    get_target_property(upstream ${target} SOURCE_DIR)
    get_target_property(sources ${target} SOURCES)
    set(output "${CMAKE_CURRENT_BINARY_DIR}/personal-sources/${component}")
    file(GLOB_RECURSE inputs CONFIGURE_DEPENDS "${upstream}/*")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
        "${PERSONAL_PATCH_TOOL}" ${PERSONAL_PATCH_MODULES} ${inputs})
    execute_process(COMMAND "${Python3_EXECUTABLE}" "${PERSONAL_PATCH_TOOL}"
        --component "${component}" --source "${upstream}" --output "${output}"
        RESULT_VARIABLE result OUTPUT_VARIABLE log ERROR_VARIABLE errors)
    if(NOT result EQUAL 0)
        message(FATAL_ERROR "${component} adaptation failed: ${log}${errors}")
    endif()
    foreach(source IN LISTS sources)
        get_filename_component(original "${source}" ABSOLUTE BASE_DIR "${upstream}")
        file(RELATIVE_PATH relative "${upstream}" "${original}")
        if(NOT relative MATCHES "^\\.\\." AND EXISTS "${output}/${relative}")
            set_source_files_properties("${original}" TARGET_DIRECTORY ${target} PROPERTIES HEADER_FILE_ONLY TRUE)
            target_sources(${target} PRIVATE "${output}/${relative}")
        endif()
    endforeach()
    target_include_directories(${target} BEFORE PRIVATE "${output}" "${CMAKE_CURRENT_SOURCE_DIR}")
endfunction()
