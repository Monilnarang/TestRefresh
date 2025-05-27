package refactor2refresh;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

//    @Test
//    void testDeposit() {
//        BankAccount account = new BankAccount(100.0);
//        account.deposit(50.0);
//        assertEquals(150.0, account.getBalance());
//        account.withdraw(30.0);
//        assertEquals(70.0, account.getBalance());
//        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
//            account.withdraw(150.0);
//        });
//        assertEquals("Insufficient balance", exception.getMessage());
//    }

    @Test
    void testDeposite() {
        Account account1 = new Account(0.0);
        Account account2 = new Account(100.0);
        Account account3 = new Account(57.23);

        account1.deposit(50.0);
        account2.deposit(50.0);
        account3.deposit(50.0);

        assertEquals(50.0, account1.getBalance());
        assertEquals(150.0, account2.getBalance());
        assertEquals(107.23, account3.getBalance());

        account1.withdraw(30.0);
        assertEquals(70.0, account1.getBalance());
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            account1.withdraw(150.0);
        });
        assertEquals("Insufficient balance", exception.getMessage());
    }

    @Test
    void testNewAccount(){
        Account account = new Account(100.0, "John Doe");

        assertEquals(100.0, account.getBalance());

        assertEquals("John Doe", account.getName());
    }

    @Test
    void testWithdrawInsufficientBalance() {
        Account account = new Account(100.0);
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            account.withdraw(150.0);
        });
        assertEquals("Insufficient balance", exception.getMessage());
    }

    @Test
    void testNegativeDeposit() {
        Account account = new Account(100.0);
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            account.deposit(-20.0);
        });
        assertEquals("Deposit amount must be positive", exception.getMessage());
    }

    @Test
    void testNegativeInitialBalance() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            new Account(-50.0);
        });
        assertEquals("Initial balance cannot be negative", exception.getMessage());
    }

    @Test
    void testWithDraw() {
        Account account = new Account(100.0);
        account.withdraw(50.0);
        assertEquals(50.0, account.getBalance());

        Account account2 = new Account(555.0);
        account.withdraw(50.0);
        assertEquals(505.0, account.getBalance());
    }

}