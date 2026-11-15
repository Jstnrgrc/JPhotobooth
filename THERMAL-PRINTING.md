# Direct thermal printing

The customer taps **Print receipt** in the strip editor. The app renders the chosen border and sends black-and-white ESC/POS raster data directly. **Receipt sent** means the data was transmitted, not that the printer verified physical output.

1. In **Admin → Printer setup**, select a paired Bluetooth Classic SPP printer, or connect a standard USB Printer Class device with an Android USB host/OTG adapter. Grant Nearby Devices or USB access as prompted.
2. Set the documented printable dot width. Try 576 as a starting point for an 80 mm model if the specification is unavailable. Enable automatic cutting only if the hardware supports it.
3. Tap **Test print** and verify the gradient, alignment, paper feed and (if enabled) cut. Then print a full photo receipt.
4. If transmission stops part way, check the printer and use **Print history and recovery** to confirm or deliberately reprint. The app will not automatically re-send uncertain output.

Supported target: ESC/POS raster command GS v 0 over Bluetooth Classic SPP or standard USB printer bulk output. BLE-only devices, USB serial adapters, and proprietary/vendor-only protocols need their own driver. No specific model has been tested yet. Before buying, request documentation and a sample image print.
