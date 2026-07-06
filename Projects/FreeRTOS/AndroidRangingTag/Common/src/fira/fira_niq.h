/**
 *  @file     fira_niq.h
 *
 *  @brief    Fira for Qorvo Nearby Interaction
 *
 * @author    Qorvo Applications
 *
 * @copyright SPDX-FileCopyrightText: Copyright (c) 2024-2025 Qorvo US, Inc.
 *            SPDX-License-Identifier: LicenseRef-QORVO-2
 *
 */

#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "niq.h"

typedef struct android_ranging_tag_fira_diag_s
{
    uint32_t session_id;
    uint16_t short_addr;
    uint16_t destination_short_addr;
    uint8_t device_type;
    uint8_t device_role;
    uint8_t ranging_round_usage;
    uint8_t rframe_config;
    uint8_t sfd_id;
    uint8_t channel_number;
    uint8_t preamble_code_index;
    uint32_t slot_duration_rstu;
    uint32_t block_duration_ms;
    uint32_t round_duration_slots;
    uint8_t round_hopping;
    uint8_t sts_config;
    uint8_t number_of_sts_segments;
    uint8_t sts_length;
    uint8_t result_report_config;
    uint8_t multi_node_mode;
    uint8_t schedule_mode;
    uint8_t vupper64[8];
} android_ranging_tag_fira_diag_t;

void StartUWB(fira_device_configure_t *config, void *user_ctx);
void StopUWB(uint32_t session_id, void *user_ctx);
void PrepareUwbCalibration(void);
error_e CreateQaniTask(void);
bool android_ranging_tag_get_fira_diag(android_ranging_tag_fira_diag_t *diag);
