import org.checkerframework.checker.builder.qual.*;


public class ReturnsReceiverTest {

	private class Animals{
		private String name;

		@ReturnsReceiver
		public Animals setName(String name) {
			this.name = name;
			return this;	
		}

		@ReturnsReceiver
		public String getName() {
			return this.name;
		}

	}

	void doStuffCorrect() {
		Animals dog = new Animals();
		System.out.printf("testing");
		dog.setName("Toby");
	}

	void doStuffWrong() {
		Animals dog = new Animals();
		dog.setName("Toby");
		dog.getName();
	}


}
