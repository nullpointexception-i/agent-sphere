package com.buukle.agent.common.util;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public class BaseController {
    protected ResponseEntity<?> ok() {
        return ResponseEntity.ok().build();
    }

    protected <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok(body);
    }

    protected <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    protected ResponseEntity<?> deleted() {
        return ResponseEntity.noContent().build();
    }
}
