record Account(String email) {}
class EvalCheckedEmail {
    String normalize(Account account) {
        if (account == null || account.email() == null) return "";
        return account.email().trim().toLowerCase();
    }
}
