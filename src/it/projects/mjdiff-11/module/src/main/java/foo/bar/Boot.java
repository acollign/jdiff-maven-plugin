package foo.bar;

public class Boot
{

    /**
     * @param args
     */
    public static void main( String[] args )
    {
        Boot b = new Boot();
        b.doMain( args );
    }
    
    protected void doMain( String[] args ) throws BootException
    {
        //@todo
    }
}
