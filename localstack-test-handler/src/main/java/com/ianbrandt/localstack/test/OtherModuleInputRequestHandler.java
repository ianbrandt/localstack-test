package com.ianbrandt.localstack.test;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class OtherModuleInputRequestHandler implements RequestHandler<OtherModuleInput, String> {

	public String handleRequest(final OtherModuleInput otherModuleInput, final Context context) {

		return otherModuleInput.getOtherTestProperty();
	}
}
