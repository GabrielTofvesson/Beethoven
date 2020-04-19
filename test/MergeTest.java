public class MergeTest {

    private final String s;


    public MergeTest(){
        s = "Hello";
    }

    public MergeTest(String s) {
        this.s = s;
    }

    public String test(){
        return s + "Test";
    }

    public String test1(){
        return s;
    }
}
