"""Constants for the Sunmi Printer integration."""
from __future__ import annotations

DOMAIN = "sunmi_printer"

DEFAULT_PORT = 8081
DEFAULT_NAME = "Sunmi Printer"

CONF_DEFAULT_TYPE = "default_type"
CONF_DEFAULT_LINES_AFTER = "default_lines_after"
CONF_DEFAULT_TITLE_SIZE = "default_title_size"
CONF_DEFAULT_CONTENT_SIZE = "default_content_size"

DEFAULT_TYPE = "plain"
DEFAULT_LINES_AFTER = 3
DEFAULT_TITLE_SIZE = 32
DEFAULT_CONTENT_SIZE = 26

# Alignment values as understood by the device's PrintJob.alignment (int).
ALIGNMENT_LEFT = "left"
ALIGNMENT_CENTER = "center"
ALIGNMENT_RIGHT = "right"

ALIGNMENT_TO_INT = {
    ALIGNMENT_LEFT: 0,
    ALIGNMENT_CENTER: 1,
    ALIGNMENT_RIGHT: 2,
}

ALIGNMENT_OPTIONS = list(ALIGNMENT_TO_INT)

# PrintJob "type" values usable as the notify entity's default layout.
NOTIFY_TYPE_OPTIONS = ["plain", "centered", "header_body", "boxed", "banner"]

# PrintJob "type" values selectable from the print_text service (types with
# no dedicated service of their own).
PRINT_TEXT_TYPE_OPTIONS = ["plain", "centered", "header_body"]

SERVICE_PRINT_TEXT = "print_text"
SERVICE_PRINT_BOXED = "print_boxed"
SERVICE_PRINT_BANNER = "print_banner"
SERVICE_PRINT_LIST = "print_list"
SERVICE_PRINT_ALERT = "print_alert"
SERVICE_PRINT_QR = "print_qr"
SERVICE_PRINT_BARCODE = "print_barcode"
SERVICE_PRINT_IMAGE = "print_image"
SERVICE_PRINT_RAW = "print_raw"
