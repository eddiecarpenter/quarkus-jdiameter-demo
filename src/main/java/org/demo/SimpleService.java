package org.demo;

import io.quarkiverse.diameter.DiameterService;
import lombok.extern.slf4j.Slf4j;
import org.jdiameter.api.*;
import org.jdiameter.api.cca.ServerCCASession;
import org.jdiameter.api.cca.ServerCCASessionListener;
import org.jdiameter.api.cca.events.JCreditControlAnswer;
import org.jdiameter.api.cca.events.JCreditControlRequest;
import org.jdiameter.common.impl.DiameterUtilities;
import org.jdiameter.common.impl.app.cca.JCreditControlAnswerImpl;

@DiameterService
@Slf4j
public class SimpleService implements ServerCCASessionListener
{
	@Override
	public void doCreditControlRequest(ServerCCASession session, JCreditControlRequest request) throws InternalException, IllegalDiameterStateException, RouteException, OverloadException
	{
		long startTime = System.currentTimeMillis();
		DiameterUtilities.printMessage(request.getMessage());

		JCreditControlAnswer answer = new JCreditControlAnswerImpl((Request) request.getMessage(), ResultCode.CREDIT_CONTROL_LIMIT_REACHED);

		AvpSet ccrAvps = request.getMessage().getAvps();
		AvpSet ccaAvps = answer.getMessage().getAvps();

		answer.getMessage().setError(true);
		ccaAvps.addAvp(ccrAvps.getAvp(Avp.CC_REQUEST_TYPE));
		ccaAvps.addAvp(ccrAvps.getAvp(Avp.CC_REQUEST_NUMBER));

		session.sendCreditControlAnswer(answer);
		DiameterUtilities.printMessage(answer.getMessage());
		LOG.debug("Answered in {}ms", System.currentTimeMillis() - startTime);
	}
}
