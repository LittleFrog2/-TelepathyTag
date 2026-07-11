/**
 * @file      android_oob.c
 *
 * @brief     Android UWB OOB protocol over QNIS.
 */

#include "android_oob.h"

#include <string.h>

#include "app_ble.h"
#include "niq.h"
#include "qlog.h"

#define ANDROID_OOB_MAGIC_0 'U'
#define ANDROID_OOB_MAGIC_1 'T'
#define ANDROID_OOB_MAGIC_2 'O'
#define ANDROID_OOB_MAGIC_3 'B'
#define ANDROID_OOB_VERSION 1

#define ANDROID_OOB_MSG_START_SESSION 0x02
#define ANDROID_OOB_MSG_ACK           0x82
#define ANDROID_OOB_MSG_FIND_PHONE    0x90

#define ANDROID_OOB_START_LEN         34
#define ANDROID_OOB_ACK_LEN           13
#define ANDROID_OOB_PAYLOAD_LEN       (ANDROID_OOB_START_LEN - 8)
#define ANDROID_OOB_STATIC_STS_KEY_LEN 8

#define ANDROID_OOB_STATUS_OK         0
#define ANDROID_OOB_STATUS_BAD_LEN    1
#define ANDROID_OOB_STATUS_BAD_FIELD  2

#define ANDROID_OOB_DEBUG_DIAG_LEN    4
#define ANDROID_OOB_DIAG_PENDING_ACK  0x0100
#define ANDROID_OOB_DIAG_STARTED      0x0000
#define ANDROID_OOB_FIRA_DIAG_LEN     47
#define ANDROID_OOB_INVALID_CONN      0xFFFF

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

extern fira_device_configure_t fira_config;
extern uint16_t current_conn_handle;
extern void StartUWB(fira_device_configure_t *config, void *user_ctx);
extern bool android_ranging_tag_get_fira_diag(android_ranging_tag_fira_diag_t *diag);

static uint8_t find_phone_seq;
static uint8_t find_phone_adv_active;

static uint16_t get_u16_le(const uint8_t *data)
{
    return (uint16_t)data[0] | ((uint16_t)data[1] << 8);
}

static uint32_t get_u32_le(const uint8_t *data)
{
    return (uint32_t)data[0] |
           ((uint32_t)data[1] << 8) |
           ((uint32_t)data[2] << 16) |
           ((uint32_t)data[3] << 24);
}

static void put_u16_le(uint8_t *data, uint16_t value)
{
    data[0] = (uint8_t)(value & 0xFF);
    data[1] = (uint8_t)((value >> 8) & 0xFF);
}

static void put_u32_le(uint8_t *data, uint32_t value)
{
    data[0] = (uint8_t)(value & 0xFF);
    data[1] = (uint8_t)((value >> 8) & 0xFF);
    data[2] = (uint8_t)((value >> 16) & 0xFF);
    data[3] = (uint8_t)((value >> 24) & 0xFF);
}

static bool has_android_oob_magic(const uint8_t *data, uint16_t len)
{
    return len >= 6 &&
           data[0] == ANDROID_OOB_MAGIC_0 &&
           data[1] == ANDROID_OOB_MAGIC_1 &&
           data[2] == ANDROID_OOB_MAGIC_2 &&
           data[3] == ANDROID_OOB_MAGIC_3 &&
           data[4] == ANDROID_OOB_VERSION;
}

static void send_start_ack(uint16_t conn_handle, uint8_t status, uint32_t session_id)
{
    uint8_t ack[ANDROID_OOB_ACK_LEN] = {
        ANDROID_OOB_MAGIC_0,
        ANDROID_OOB_MAGIC_1,
        ANDROID_OOB_MAGIC_2,
        ANDROID_OOB_MAGIC_3,
        ANDROID_OOB_VERSION,
        ANDROID_OOB_MSG_ACK,
    };

    put_u16_le(&ack[6], ANDROID_OOB_ACK_LEN - 8);
    ack[8] = status;
    put_u32_le(&ack[9], session_id);

    send_qnis_data(conn_handle, ack, sizeof(ack));
}

static void send_diag(uint16_t conn_handle, uint16_t code)
{
    uint8_t diag[ANDROID_OOB_DEBUG_DIAG_LEN] = {0xFE, 0xFF, 0, 0};
    put_u16_le(&diag[2], code);
    send_qnis_data(conn_handle, diag, sizeof(diag));
}

static void send_fira_param_diag(uint16_t conn_handle)
{
    android_ranging_tag_fira_diag_t diag;
    uint8_t out[ANDROID_OOB_FIRA_DIAG_LEN] = {0xFE, 0xFD, 0x01, 0x01, 42};

    if (!android_ranging_tag_get_fira_diag(&diag))
    {
        return;
    }

    put_u32_le(&out[5], diag.session_id);
    put_u16_le(&out[9], diag.short_addr);
    put_u16_le(&out[11], diag.destination_short_addr);
    out[13] = diag.device_type;
    out[14] = diag.device_role;
    out[15] = diag.ranging_round_usage;
    out[16] = diag.rframe_config;
    out[17] = diag.sfd_id;
    out[18] = diag.channel_number;
    out[19] = diag.preamble_code_index;
    put_u32_le(&out[20], diag.slot_duration_rstu);
    put_u32_le(&out[24], diag.block_duration_ms);
    put_u32_le(&out[28], diag.round_duration_slots);
    out[32] = diag.round_hopping;
    out[33] = diag.sts_config;
    out[34] = diag.number_of_sts_segments;
    out[35] = diag.sts_length;
    out[36] = diag.result_report_config;
    out[37] = diag.multi_node_mode;
    out[38] = diag.schedule_mode;
    memcpy(&out[39], diag.vupper64, sizeof(diag.vupper64));

    send_qnis_data(conn_handle, out, sizeof(out));
}

void android_oob_handle_find_phone_button(void)
{
    uint8_t event[10] = {
        ANDROID_OOB_MAGIC_0,
        ANDROID_OOB_MAGIC_1,
        ANDROID_OOB_MAGIC_2,
        ANDROID_OOB_MAGIC_3,
        ANDROID_OOB_VERSION,
        ANDROID_OOB_MSG_FIND_PHONE,
        0x02,
        0x00,
        0x01,
        0x00,
    };

    event[9] = find_phone_seq++;

    if (current_conn_handle == ANDROID_OOB_INVALID_CONN)
    {
        find_phone_adv_active = find_phone_adv_active ? 0 : 1;
        QLOGI("Find-phone advertising event: active=%u seq=%u", find_phone_adv_active, event[9]);
        ble_set_find_phone_advertising(find_phone_adv_active, event[9]);
        return;
    }

    QLOGI("Find-phone button event sent: seq=%u", event[9]);
    send_qnis_data(current_conn_handle, event, sizeof(event));
}

static bool parse_start_session(uint16_t conn_handle, const uint8_t *data, uint16_t len)
{
    const uint16_t payload_len = (len >= 8) ? get_u16_le(&data[6]) : 0;
    uint32_t session_id = 0;

    if (len >= 12)
    {
        session_id = get_u32_le(&data[8]);
    }

    if (len != ANDROID_OOB_START_LEN || payload_len != ANDROID_OOB_PAYLOAD_LEN)
    {
        QLOGE("Android OOB START bad len: len=%u payload=%u", len, payload_len);
        send_start_ack(conn_handle, ANDROID_OOB_STATUS_BAD_LEN, session_id);
        return true;
    }

    const uint16_t controller_addr = get_u16_le(&data[12]);
    const uint16_t controlee_addr = get_u16_le(&data[14]);
    const uint8_t channel = data[16];
    const uint8_t preamble = data[17];
    const uint16_t slot_duration = get_u16_le(&data[18]);
    const uint16_t block_duration = get_u16_le(&data[20]);
    const uint8_t round_duration_slots = data[22];
    const uint8_t sts_config = data[24];
    const uint8_t key_len = data[25];

    if (session_id == 0 || controller_addr == 0 || controlee_addr == 0 || key_len != ANDROID_OOB_STATIC_STS_KEY_LEN)
    {
        QLOGE(
            "Android OOB START bad fields: session=%lu controller=0x%04x controlee=0x%04x keyLen=%u",
            (unsigned long)session_id,
            controller_addr,
            controlee_addr,
            key_len
        );
        send_start_ack(conn_handle, ANDROID_OOB_STATUS_BAD_FIELD, session_id);
        return true;
    }

    fira_config.role = 0;
    fira_config.Session_ID = session_id;
    fira_config.Channel_Number = channel;
    fira_config.Preamble_Code = preamble;
    fira_config.Slot_Duration_RSTU = slot_duration;
    fira_config.Block_Duration_ms = block_duration;
    fira_config.Round_Duration_RSTU = (uint32_t)slot_duration * (uint32_t)round_duration_slots;
    fira_config.STS_Config = sts_config;
    fira_config.Round_Hopping = 1;
    fira_config.SRC_ADDR[0] = (uint8_t)(controlee_addr & 0xFF);
    fira_config.SRC_ADDR[1] = (uint8_t)((controlee_addr >> 8) & 0xFF);
    fira_config.DST_ADDR[0] = (uint8_t)(controller_addr & 0xFF);
    fira_config.DST_ADDR[1] = (uint8_t)((controller_addr >> 8) & 0xFF);
    fira_config.Number_of_Controlee = 1;

    fira_config.Vendor_ID[0] = data[26];
    fira_config.Vendor_ID[1] = data[27];
    memcpy(fira_config.Static_STS_IV, &data[28], sizeof(fira_config.Static_STS_IV));

    QLOGI(
        "Android OOB START ok: session=0x%08lx controller=0x%04x controlee=0x%04x ch=%u preamble=%u",
        (unsigned long)session_id,
        controller_addr,
        controlee_addr,
        channel,
        preamble
    );

    send_start_ack(conn_handle, ANDROID_OOB_STATUS_OK, session_id);
    send_diag(conn_handle, ANDROID_OOB_DIAG_PENDING_ACK);

    StartUWB(&fira_config, NULL);
    send_diag(conn_handle, ANDROID_OOB_DIAG_STARTED);
    send_fira_param_diag(conn_handle);

    return true;
}

bool android_oob_handle_ble_rx(uint16_t conn_handle, const uint8_t *data, uint16_t len)
{
    if (len == 1 && data[0] == 0x01)
    {
        uint8_t echo = 0x01;
        QLOGI("Android OOB probe received");
        send_qnis_data(conn_handle, &echo, sizeof(echo));
        return true;
    }

    if (!has_android_oob_magic(data, len))
    {
        return false;
    }

    if (data[5] == ANDROID_OOB_MSG_START_SESSION)
    {
        return parse_start_session(conn_handle, data, len);
    }

    QLOGW("Unsupported Android OOB message: 0x%02x", data[5]);
    return true;
}
