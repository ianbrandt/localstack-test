package com.ianbrandt.test.localstack;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class SameModuleInputRequestHandler implements RequestHandler<SameModuleInput, String> {

	public String handleRequest(final SameModuleInput sameModuleInput, final Context context) {

		return sameModuleInput.getTestProperty();
	}
}
