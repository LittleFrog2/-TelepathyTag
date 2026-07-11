/**
 * @file      android_oob.h
 *
 * @brief     Android UWB OOB protocol over QNIS.
 */

#pragma once

#include <stdbool.h>
#include <stdint.h>

bool android_oob_handle_ble_rx(uint16_t conn_handle, const uint8_t *data, uint16_t len);
void android_oob_handle_find_phone_button(void);
