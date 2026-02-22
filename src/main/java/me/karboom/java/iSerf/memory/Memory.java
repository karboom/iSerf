package me.karboom.java.iSerf.memory;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
abstract public class Memory {
    protected List<Item> items =  new ArrayList<>();



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
