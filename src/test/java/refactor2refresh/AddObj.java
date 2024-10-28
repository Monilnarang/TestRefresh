package refactor2refresh;

public class AddObj {

    public int value;
    public AddObj(int v) {
        this.value = v;
    }

    public AddObj(String v) {
    }

    public static int add(AddObj a, AddObj b) {
        return a.value + b.value;
    }
}
