package io.jans;

import io.jans.facade.FacadeMain;

/**
 * Processes the traces that {@link Main} wrote to the output directory: converts them to FACADE actions and
 * contexts (JSONL and TFRecord), then runs the FACADE commands for training and anomaly detection.
 */
public class FleetMain {

    public static void main(String[] args) throws Exception {
        FacadeMain.main(args);
    }
}
