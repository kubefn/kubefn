package com.kubefn.operator;

import io.javaoperatorsdk.operator.Operator;
import com.kubefn.operator.reconciler.KubeFnGroupReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OperatorMain {

    private static final Logger log = LoggerFactory.getLogger(OperatorMain.class);

    public static void main(String[] args) {
        log.info("Starting KubeFn Operator");

        try {
            Operator operator = new Operator();
            operator.register(new KubeFnGroupReconciler());
            operator.start();

            log.info("KubeFn Operator started, watching for KubeFnGroup resources");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down KubeFn Operator");
                operator.stop();
            }));

            // Block main thread
            Thread.currentThread().join();
        } catch (Exception e) {
            log.error("Failed to start KubeFn Operator", e);
            System.exit(1);
        }
    }
}
