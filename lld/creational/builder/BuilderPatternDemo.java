package lld.creational.builder;


// ============================================================================
// 1. Product Class (Immutable with private constructor)
// ============================================================================
class User {
    private final String firstName; // final field ensures immutability
    private final String lastName;  // final field ensures immutability
    private final int age;          // final field ensures immutability
    private final String phone;     // final field ensures immutability

    // Private constructor: can ONLY be instantiated by the nested Builder class
    private User(UserBuilder builder) {
        this.firstName = builder.firstName;
        this.lastName = builder.lastName;
        this.age = builder.age;
        this.phone = builder.phone;
    }

    // Getters (No Setter methods!)
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public int getAge() { return age; }
    public String getPhone() { return phone; }

    @Override
    public String toString() {
        return "User{" +
                "firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", age=" + age +
                ", phone='" + phone + '\'' +
                '}';
    }

    // ============================================================================
    // 2. Nested Static Builder Class
    // ============================================================================
    public static class UserBuilder {
        private final String firstName; // Required field (marked final)
        private final String lastName;  // Required field (marked final)
        private int age = 0;            // Optional field (default value)
        private String phone = "N/A";   // Optional field (default value)

        // Constructor enforces required parameters
        public UserBuilder(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }

        // Fluent methods for optional parameters (return 'this' for chaining)
        public UserBuilder age(int age) {
            this.age = age;
            return this;
        }

        public UserBuilder phone(String phone) {
            this.phone = phone;
            return this;
        }

        // The build method: triggers validations and constructs the final User
        public User build() {
            User user = new User(this);
            validateUserObject(user);
            return user;
        }

        private void validateUserObject(User user) {
            if (user.getAge() < 0) {
                throw new IllegalArgumentException("Age cannot be negative! Found: " + user.getAge());
            }
        }
    }
}

// ============================================================================
// 3. Execution Demo
// ============================================================================
public class BuilderPatternDemo {
    public static void main(String[] args) {
        System.out.println("--- LLD Builder Design Pattern Demo ---\n");

        // Construct User 1: Only required fields
        User user1 = new User.UserBuilder("John", "Doe").build();
        System.out.println("User 1: " + user1);

        // Construct User 2: Required + Age (fluent method chaining)
        User user2 = new User.UserBuilder("Alice", "Smith")
                .age(28)
                .build();
        System.out.println("User 2: " + user2);

        // Construct User 3: Fully populated profile
        User user3 = new User.UserBuilder("Bob", "Jones")
                .age(35)
                .phone("123-456-7890")
                .build();
        System.out.println("User 3: " + user3);

        // Demonstrate Validation Failure (shows step-by-step safety check)
        System.out.println("\n[Action] Attempting to construct user with negative age...");
        try {
            User invalidUser = new User.UserBuilder("Charlie", "Brown")
                    .age(-5) // Should throw exception
                    .build();
        } catch (IllegalArgumentException e) {
            System.out.println("Validation Caught Exception: " + e.getMessage());
        }
    }
}
