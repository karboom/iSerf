package me.karboom.java.iSlogger.memory;

import java.util.ArrayList;
import java.util.List;

abstract public class Memory {
    List<Item> items =  new ArrayList<>();

    public Memory() {
    }

    abstract public void sync();
    abstract public void load();

    public void add(Item item) {
        items.add(item);
        sync();
    }
    public void remove(Item item) {
        items.remove(item);
        sync();
    }
    public void clear(){
        items.clear();
        sync();
    }
    public void update(Item item) {
    }
    public List<Item> get() {
        return items;
    }
}
