This directory contains tests for the typesafe builder checker.

Each test suite is meant to reflect a particular application domain. The test suites are:
* `basic`: tests of subtyping and inference that don't reflect a specify application domain.
* `aws-describeimages`: AWS DescribeImagesRequest, vulnerable to AMI sniping attack
* `guice`: [Google Guice dependency injection framework](https://github.com/google/guice)
* `lombok`: [Lombok code generator](https://github.com/rzwitserloot/lombok)
