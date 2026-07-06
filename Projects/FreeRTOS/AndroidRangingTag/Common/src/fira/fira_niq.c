/**
 *  @file     fira_niq.c
 *
 *  @brief    Fira processes control
 *
 * @author    Qorvo Applications
 *
 * @copyright SPDX-FileCopyrightText: Copyright (c) 2024-2025 Qorvo US, Inc.
 *            SPDX-License-Identifier: LicenseRef-QORVO-2
 *
 */

#include <inttypes.h>
#include <stdint.h>
#include <string.h>
#include "HAL_error.h"
#include "common_fira.h"
#include "deca_error.h"
#include "HAL_watchdog.h"
#include "uwbmac/uwbmac.h"
#include "fira_niq.h"
#include "common_fira.h"
#include "qmalloc.h"
#include "qirq.h"
#include "uwb_utils.h"
#include "qlog.h"
#include "uwb_convert.h"
#include "common_fira.h"
#include "llhw.h"
#include "qplatform.h"
#include "qsignal.h"
#include "qthread.h"

extern struct l1_config_platform_ops l1_config_platform_ops;

#define OUTPUT_PDOA_ENABLE        (1)
#define STR_SIZE                  (256)

#define MIN_CM_DISTANCE_THRESHOLD 40
#define MAX_CM_DISTANCE_THRESHOLD 120

#define FIRA_ROLE_MASK            0x01

#define AR2U16(x)                 ((x[1] << 8) | x[0])

#define BPRF_SET_1                (1) /* SP0 IEEE SFD. */
#define BPRF_SET_2                (2) /* SP0 4z SFD. */
#define BPRF_SET_3                (3) /* SP1 4z SFD. */
#define BPRF_SET_4                (4) /* SP3 4z SFD. */
#define BPRF_SET_5                (5) /* SP1 IEEE SFD. */
#define BPRF_SET_6                (6) /* SP3 IEEE SFD. */

/** @brief Stack size in bytes for the Qani task. */
#define QANI_TASK_STACK_SIZE_BYTES 4096

/** @brief Priority of the Qani task. */
#define QANI_TASK_PRIORITY QTHREAD_PRIORITY_NORMAL

/** @brief Signal timeout in Qani Task also used to refresh the watchdog. */
#define QANI_SIGNAL_WAIT_TIMEOUT_MS 10000

extern fira_device_configure_t fira_config;

extern void send_ios_notification(uint8_t distance, char *txt_message);
extern uint16_t current_conn_handle;
extern void send_qnis_data(uint16_t conn_handle, uint8_t *buffer, uint16_t data_len);

static void report_cb(const struct fira_twr_ranging_results *results, void *user_data);
static void handle_helper_events(enum fira_helper_cb_type cb_type, const void *content, void *user_data);
static void fira_set_params(fira_param_t *fira_param, fira_device_configure_t *config, bool controller_type);
static error_e fira_niq_app_process_init(bool controller);
static void qani_task(void *arg);
static void fira_start_niq(void);
static void fira_stop_niq(void);

static struct string_measurement output_result;
static struct uwbmac_context *uwbmac_ctx = NULL;
static struct fira_context fira_ctx;
static uint32_t session_id = 42;
static uint32_t session_handle = 0;
static bool is_started = false;

static struct qthread *qani_thread;
static struct qsignal *qani_signal = NULL;
static uint8_t android_runtime_diag_seq = 0;

#if defined(ANDROID_RANGING_TAG_BUILD)
static void send_android_runtime_diag(uint8_t type, uint8_t arg0, uint8_t arg1, uint32_t value)
{
    uint8_t diag[10] = {0xFE, 0xFC, type, android_runtime_diag_seq++, arg0, arg1, 0, 0, 0, 0};

    if (current_conn_handle == 0xFFFF)
    {
        return;
    }

    diag[6] = (uint8_t)(value & 0xFF);
    diag[7] = (uint8_t)((value >> 8) & 0xFF);
    diag[8] = (uint8_t)((value >> 16) & 0xFF);
    diag[9] = (uint8_t)((value >> 24) & 0xFF);
    send_qnis_data(current_conn_handle, diag, sizeof(diag));
}

static void send_android_range_diag(const struct fira_twr_ranging_results *results)
{
    uint8_t diag[20] = {0xFE, 0xFC, 0x10, android_runtime_diag_seq++};

    if (current_conn_handle == 0xFFFF || !results || !results->info)
    {
        return;
    }

    diag[4] = (uint8_t)results->n_measurements;
    diag[5] = 0xFF;
    diag[6] = 0xFF;
    diag[8] = (uint8_t)(results->info->block_index & 0xFF);
    diag[9] = (uint8_t)((results->info->block_index >> 8) & 0xFF);
    diag[10] = (uint8_t)((results->info->block_index >> 16) & 0xFF);
    diag[11] = (uint8_t)((results->info->block_index >> 24) & 0xFF);

    if (results->n_measurements > 0)
    {
        const struct fira_twr_measurements *rm = &results->measurements[0];
        diag[5] = rm->status;
        diag[6] = rm->slot_index;
        diag[7] = rm->rssi;
        diag[12] = (uint8_t)(rm->short_addr & 0xFF);
        diag[13] = (uint8_t)((rm->short_addr >> 8) & 0xFF);
        diag[14] = (uint8_t)(rm->distance_cm & 0xFF);
        diag[15] = (uint8_t)(((uint32_t)rm->distance_cm >> 8) & 0xFF);
        diag[16] = (uint8_t)(((uint32_t)rm->distance_cm >> 16) & 0xFF);
        diag[17] = (uint8_t)(((uint32_t)rm->distance_cm >> 24) & 0xFF);
        diag[18] = rm->nlos;
        diag[19] = rm->stopped ? 1 : 0;
    }

    send_qnis_data(current_conn_handle, diag, sizeof(diag));
}
#else
static void send_android_runtime_diag(uint8_t type, uint8_t arg0, uint8_t arg1, uint32_t value)
{
    (void)type;
    (void)arg0;
    (void)arg1;
    (void)value;
}

static void send_android_range_diag(const struct fira_twr_ranging_results *results)
{
    (void)results;
}
#endif

/**
 * @brief Qani thread that handles the start and stop of Fira TWR.
 */
static void qani_task(void *arg)
{
    bool is_start_required;

    do
    {
        Watchdog.refresh();

        if (qsignal_wait(qani_signal, (void *)&is_start_required, QANI_SIGNAL_WAIT_TIMEOUT_MS) == QERR_SUCCESS)
        {
            is_start_required ? fira_start_niq() : fira_stop_niq();
        }
    } while (1);
}

static fira_param_t fira_config_ram __attribute__((section(".rconfig")));

bool android_ranging_tag_get_fira_diag(android_ranging_tag_fira_diag_t *diag)
{
    if (!diag)
    {
        return false;
    }

    fira_param_t *fira_param = &fira_config_ram;
    memset(diag, 0, sizeof(*diag));

    diag->session_id = fira_param->session_id;
    diag->short_addr = fira_param->session.short_addr;
    if (fira_param->session.n_destination_short_address > 0)
    {
        diag->destination_short_addr = fira_param->session.destination_short_address[0];
    }
    diag->device_type = fira_param->session.device_type;
    diag->device_role = fira_param->session.device_role;
    diag->ranging_round_usage = fira_param->session.ranging_round_usage;
    diag->rframe_config = fira_param->session.rframe_config;
    diag->sfd_id = fira_param->session.sfd_id;
    diag->channel_number = fira_param->session.channel_number;
    diag->preamble_code_index = fira_param->session.preamble_code_index;
    diag->slot_duration_rstu = fira_param->session.slot_duration_rstu;
    diag->block_duration_ms = fira_param->session.block_duration_ms;
    diag->round_duration_slots = fira_param->session.round_duration_slots;
    diag->round_hopping = fira_param->session.round_hopping ? 1 : 0;
    diag->sts_config = fira_param->session.sts_config;
    diag->number_of_sts_segments = fira_param->session.number_of_sts_segments;
    diag->sts_length = fira_param->session.sts_length;
    diag->result_report_config = fira_param->session.result_report_config;
    diag->multi_node_mode = fira_param->session.multi_node_mode;
    diag->schedule_mode = fira_param->session.schedule_mode;
    memcpy(diag->vupper64, fira_param->session.vupper64, sizeof(diag->vupper64));

    return true;
}

static error_e fira_niq_app_process_init(bool controller)
{
    fira_param_t *fira_param = &fira_config_ram;
    struct fbs_session_init_rsp rsp;
    uint16_t string_len;
    enum qerr err;

    fira_set_params(fira_param, (void *)&fira_config, controller);

    session_id = fira_param->session_id;
    string_len = STR_SIZE * (controller ? fira_param->controlees_params.n_controlees : 1);

    output_result.str = qmalloc(string_len);
    if (!(output_result.str))
    {
        QLOGE("Not enough memory");
        return _ERR_Cannot_Alloc_Memory;
    }
    output_result.len = string_len;

    fira_prepare_measurement_sequence(uwbmac_ctx, &fira_param->session, false);

    /* Unset promiscuous to accept only filtered frames. */
    uwbmac_set_promiscuous_mode(uwbmac_ctx, true);
    /* Set local short address. */
    uwbmac_set_short_addr(uwbmac_ctx, fira_param->session.short_addr);
    /* Register report cb */
    err = fira_helper_open(&fira_ctx, uwbmac_ctx, &handle_helper_events, "endless", 0, &output_result);
    if (err != QERR_SUCCESS)
    {
        QLOGE("fira_helper_open failed");
        return _ERR;
    }

    /* Set fira scheduler */
    err = fira_helper_set_scheduler(&fira_ctx);
    if (err != QERR_SUCCESS)
    {
        QLOGE("fira_helper_set_scheduler failed");
        return _ERR;
    }

    /* Init session */
    err = fira_helper_init_session(&fira_ctx, session_id, QUWBS_FBS_SESSION_TYPE_RANGING_NO_IN_BAND_DATA, &rsp);
    if (err != QERR_SUCCESS)
    {
        QLOGE("fira_helper_init_session failed");
        return _ERR;
    }
    session_handle = rsp.session_handle;

    /* Set session parameters */
    err = fira_set_session_parameters(&fira_ctx, session_handle, &fira_param->session);
    if (err != QERR_SUCCESS)
    {
        QLOGE("fira_set_session_parameters failed");
        return _ERR;
    }

    if (controller)
    {
        /* Add controlee session parameters */
        err = fira_helper_add_controlee(&fira_ctx, session_handle, (const struct controlee_parameters *)&fira_param->controlees_params);
        if (err != QERR_SUCCESS)
        {
            QLOGE("fira_helper_add_controlee failed");
            return _ERR;
        }
    }

    return _NO_ERR;
}

/**
 * @brief Fill the fira_param with config from fira_device configure
 */
static void fira_set_params(fira_param_t *fira_param, fira_device_configure_t *config, bool is_controller)
{
    /* time0_ns should be converted from UWB_Init_Time_ms. However, in niq the value is in ms whereas in uwb-stack
     * the value is an absolute time of UWB device domain. */
    fira_param->session.time0_ns = 0;

    /* Session Config */
    fira_param->short_addr = AR2U16(config->SRC_ADDR);
    fira_param->session_id = config->Session_ID;

    if (is_controller)
    {
        fira_param->session.device_type = QUWBS_FBS_DEVICE_TYPE_CONTROLLER;
        fira_param->session.device_role = QUWBS_FBS_DEVICE_ROLE_INITIATOR;
    }
    else
    {
        fira_param->session.device_type = QUWBS_FBS_DEVICE_TYPE_CONTROLEE;
        fira_param->session.device_role = QUWBS_FBS_DEVICE_ROLE_RESPONDER;
    }

    /* Only supporting Deferred DS-TWR */
    fira_param->session.ranging_round_usage = config->Ranging_Round_Usage;
    fira_param->session.rframe_config = config->Rframe_Config;
    fira_param->session.sfd_id = (config->SP0_PHY_Set == BPRF_SET_2) ? (FIRA_SFD_ID_2) : (FIRA_SFD_ID_0);
    fira_param->session.slot_duration_rstu = config->Slot_Duration_RSTU;
    fira_param->session.block_duration_ms = config->Block_Duration_ms;
#ifdef ANDROID_RANGING_TAG_BUILD
    fira_param->session.round_duration_slots = config->Round_Duration_RSTU / config->Slot_Duration_RSTU;
#else
    fira_param->session.round_duration_slots = 1 + config->Round_Duration_RSTU / config->Slot_Duration_RSTU; /* +1 slot to satisfy corner case when the # of RR is exact the same as the # of slots in TWR. This is fine because hopping is disabled in the NI protocol.*/
#endif
    fira_param->session.multi_node_mode = config->Multi_Node_Mode;

    /* FIX The devryption issue*/
    fira_param->session.preamble_duration = FIRA_PREAMBLE_DURATION_64;
    /* Enable Ranging round */
    fira_param->session.ranging_round_control |= fira_helper_bool_to_ranging_round_control(true, false);

    fira_param->session.round_hopping = config->Round_Hopping;
    fira_param->session.sts_config = config->STS_Config;
    fira_param->session.result_report_config |= fira_helper_bool_to_result_report_config((bool)(config->ToF_Report > 0), false, false, false);

    /* Without this addresses the tx does not work*/
    fira_param->session.short_addr = AR2U16(config->SRC_ADDR);

    fira_param->session.destination_short_address[0] = AR2U16(config->DST_ADDR);
    fira_param->session.n_destination_short_address = 1;
    fira_param->controlees_params.n_controlees = config->Number_of_Controlee;
    fira_param->controlees_params.controlees[0].address = fira_param->session.destination_short_address[0];

    /* Scheduler mode */
    fira_param->session.schedule_mode = FIRA_SCHEDULE_MODE_TIME_SCHEDULED;

    /* Static STS isolation experiment: force vUpper64 to all zeros. */
    memset(fira_param->session.vupper64, 0, sizeof(fira_param->session.vupper64));

    /* Get parameters from global configuration. */
    fira_param->session.channel_number = config->Channel_Number;
    fira_param->session.preamble_code_index = config->Preamble_Code;

    /* Set sts parameters in the session*/
    fira_param->session.number_of_sts_segments = FIRA_STS_SEGMENTS_1;
    fira_param->session.sts_length = FIRA_STS_LENGTH_64;
}

/**
 * @brief   Gets the ranging results and print it
 */
static void handle_helper_events(enum fira_helper_cb_type cb_type, const void *content, void *user_data)
{
    send_android_runtime_diag(0x01, (uint8_t)cb_type, 0, 0);

    switch (cb_type)
    {
        case FIRA_HELPER_CB_TYPE_TWR_RANGE_NTF:
            report_cb(
                (const struct fira_twr_ranging_results *)content, user_data
            );
            break;
        case FIRA_HELPER_CB_TYPE_SESSION_DATA_CREDIT_NTF:
        case FIRA_HELPER_CB_TYPE_SESSION_DATA_TRANSFER_STATUS_NTF:
        case FIRA_HELPER_CB_TYPE_DATA_MESSAGE_RCV:
            break;
        default:
            break;
    }
}

static void report_cb(const struct fira_twr_ranging_results *results, void *user_data)
{
    static bool notify = true;

    int len = 0;
    struct string_measurement *str_result = (struct string_measurement *)user_data;
    struct fira_twr_measurements *rm;
    fira_param_t *fira_param = &fira_config_ram;

    send_android_range_diag(results);

    session_id = fira_param->session_id;

    len = sprintf(str_result->str, "{\"Block\":%" PRIu32 ", \"results\":[", results->info->block_index);

    for (int i = 0; i < results->n_measurements; i++)
    {
        if (i > 0)
        {
            len += snprintf(&str_result->str[len], str_result->len - len, ",");
        }

        rm = (struct fira_twr_measurements *)(&results->measurements[i]);

        len += snprintf(&str_result->str[len], str_result->len - len, "{\"Addr\":\"0x%04x\",\"Status\":\"%s\"", rm->short_addr, (rm->status) ? ("Err") : ("Ok"));

        if (rm->status == 0)
        {
            len += snprintf(&str_result->str[len], str_result->len - len, ",\"D_cm\":%d", (int)rm->distance_cm);

            struct uwbmac_device_info device_info;
            if (uwbmac_get_device_info(uwbmac_ctx, &device_info))
                return;

            const aoa_supported_t is_aoa = uwb_device_is_aoa(device_info.dev_id);
            if (is_aoa == AOA_SUPPORTED)
            {
                len += snprintf(&str_result->str[len], str_result->len - len, ",\"LPDoA_deg\":%0.2f,\"LAoA_deg\":%0.2f,\"LFoM\":%d,\"RAoA_deg\":%0.2f", convert_aoa_2pi_q16_to_deg(rm->local_aoa_measurements[0].pdoa_2pi), convert_aoa_2pi_q16_to_deg(rm->local_aoa_measurements[0].aoa_2pi), rm->local_aoa_measurements[0].aoa_fom_100, convert_aoa_2pi_q16_to_deg(rm->remote_aoa_azimuth_2pi));
            }

            /* Take action based on distance */
            if (notify && (rm->distance_cm < MIN_CM_DISTANCE_THRESHOLD))
            {
#if LEDS_NUMBER > 1
                /* Visual indication on the device for "inside the bubble" */
                bsp_board_led_on(BSP_BOARD_LED_2);  /* Red LED On */
                bsp_board_led_off(BSP_BOARD_LED_0); /* Green LED Off */
#endif

                /* Send message to trigger notification on the iOS side */
                send_ios_notification((uint8_t)rm->distance_cm, "You are in the secure bubble.");
                notify = false;
            }
            else if (!notify && (rm->distance_cm > MAX_CM_DISTANCE_THRESHOLD))
            {
#if LEDS_NUMBER > 1
                /* Visual indication on the device for "outside the bubble" */
                bsp_board_led_off(BSP_BOARD_LED_2); /* Red LED Off */
                bsp_board_led_on(BSP_BOARD_LED_0);  /* Green LED On */
#endif
                /* Send message to trigger notification on the iOS side */
                send_ios_notification((uint8_t)rm->distance_cm, "You are out of the secure bubble.");
                notify = true;
            }
        }
        len += snprintf(&str_result->str[len], str_result->len - len, "}");
    }

    len += snprintf(&str_result->str[len], str_result->len - len, "]");

    len += snprintf(&str_result->str[len], str_result->len - len, "}");

    QLOGI("%s", str_result->str);
}

/**
 * @fn    fira_start_niq
 * @brief Starts the niq fira.
 */
static void fira_start_niq(void)
{
    error_e err;
    bool controller = fira_config.role & FIRA_ROLE_MASK;

    send_android_runtime_diag(0x02, 0x10, 0, 0);
    err = fira_niq_app_process_init(controller);
    if (err != _NO_ERR)
    {
        send_android_runtime_diag(0x02, 0x11, 1, (uint32_t)err);
        error_handler(1, err);
    }
    send_android_runtime_diag(0x02, 0x11, 0, 0);

    enum qerr r = uwbmac_start(uwbmac_ctx);
    if (r != QERR_SUCCESS)
    {
        send_android_runtime_diag(0x02, 0x20, 1, (uint32_t)r);
        QLOGE("uwbmac_start failed");
        return;
    }
    send_android_runtime_diag(0x02, 0x20, 0, 0);

    r = fira_helper_start_session(&fira_ctx, session_handle);
    if (r != QERR_SUCCESS)
    {
        send_android_runtime_diag(0x02, 0x30, 1, (uint32_t)r);
        QLOGE("fira_helper_start_session failed");
        return;
    }
    send_android_runtime_diag(0x02, 0x30, 0, 0);
    is_started = true;
}

/**
 * @fn    fira_stop_niq
 * @brief Stop niq fira.
 */
static void fira_stop_niq(void)
{
    if (is_started)
    {
        /* Stop */
        uwbmac_stop(uwbmac_ctx);

        /* Stop session */
        int r = fira_helper_stop_session(&fira_ctx, session_handle);
        if (r != QERR_SUCCESS)
        {
            QLOGE("fira_helper_stop_session failed");
            return;
        }

        /* Uninit session */
        r = fira_helper_deinit_session(&fira_ctx, session_handle);
        if (r != QERR_SUCCESS)
        {
            QLOGE("fira_helper_deinit_session failed");
            return;
        }

        fira_helper_close(&fira_ctx);
        qfree(output_result.str);

        is_started = false;
    }
}

/**
 * @brief Create Task for Qani.
 */
error_e CreateQaniTask(void)
{
    /* Create Qani task. */
    const size_t task_size = QANI_TASK_STACK_SIZE_BYTES;
    static uint8_t *qani_task_stack;
    qani_task_stack = qmalloc(task_size);
    if (qani_task_stack == NULL)
    {
        QLOGE("Failed to allocate memory for Qani task stack");
        return _ERR_Cannot_Alloc_Memory;
    }

    /* To handle calibration and flash transactions before softdevice is up. */
    enum qerr r = fira_uwb_mcps_init(&uwbmac_ctx);
    if (r)
    {
        QLOGE("uwb_stack_init failed");
        assert(false);
    }

    qani_thread = qthread_create(qani_task, NULL, "Qani", qani_task_stack, QANI_TASK_STACK_SIZE_BYTES, QANI_TASK_PRIORITY);
    if (!qani_thread)
    {
        QLOGE("Failed to create Qani task");
        qfree(qani_task_stack);
        return _ERR_Create_Task_Bad;
    }

    /* Initialize Qani signal */
    qani_signal = qsignal_init();
    if (!qani_signal)
    {
        QLOGE("Failed to create Qani signal");
        qfree(qani_task_stack);
        return _ERR_Signal_Bad;
    }

    return _NO_ERR;
}

/**
 * @brief Send request to start UWB.
 */
void StartUWB(fira_device_configure_t *config, void *user_ctx)
{
    qsignal_raise(qani_signal, true);
}

/**
 * @brief Send request to stop UWB.
 */
void StopUWB(uint32_t session_id, void *user_ctx)
{
    qsignal_raise(qani_signal, false);
}
