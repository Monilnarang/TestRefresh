# TestRefresh
A tool to upgrade test suites. Capabilities include:
- [ ] Detecting Assertion Pasta
- [ ] Refactor them to be more readable & maintainable and less brittle?

Input is a test class name with its path. Output is a new test class in the same path with refreshed tests.
- Does this by: 
- [x] Atomising all the tests in the class
- [x] Build Up Parameterized tests from similar tests
- [x] Add value sets to new PUTs via LLM
- [x] Compare refreshed test suite with existing test suite
