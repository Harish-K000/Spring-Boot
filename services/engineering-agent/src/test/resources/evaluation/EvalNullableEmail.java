record Account(String email) {}
class EvalNullableEmail {
    String normalize(Account account) {
        return account.email().trim().toLowerCase();
    }
}
