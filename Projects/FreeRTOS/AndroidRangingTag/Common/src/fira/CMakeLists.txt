# SPDX-FileCopyrightText: Copyright (c) 2024 Qorvo US, Inc.
# SPDX-License-Identifier: LicenseRef-QORVO-2

add_library(fira_android_ranging_tag STATIC fira_niq.c)

target_include_directories(fira_android_ranging_tag PUBLIC .)

target_link_libraries(fira_android_ranging_tag PUBLIC fira niq Helpers)
