package com.vineyard.hfm.app;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ClipboardManager {

    public enum Operation {
        NONE,
        COPY,
        MOVE
		}

    private static ClipboardManager instance;

    private List<File> files;
    private Operation operation;

    private ClipboardManager() {
        this.files = new ArrayList<>();
        this.operation = Operation.NONE;
    }

    public static synchronized ClipboardManager getInstance() {
        if (instance == null) {
            instance = new ClipboardManager();
        }
        return instance;
    }

    public void setItems(List<File> files, Operation operation) {
        this.files.clear();
        this.files.addAll(files);
        this.operation = operation;
    }

    public List<File> getItems() {
        return new ArrayList<>(this.files); // Return a copy to prevent external modification
    }

    public Operation getOperation() {
        return operation;
    }

    public boolean hasItems() {
        return !files.isEmpty() && operation != Operation.NONE;
    }

    public void clear() {
        this.files.clear();
        this.operation = Operation.NONE;
    }
}
