package org.demo;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import lombok.extern.slf4j.Slf4j;
import org.jdiameter.api.*;
import org.jdiameter.api.cca.ServerCCASession;
import org.jdiameter.api.cca.ServerCCASessionListener;
import org.jdiameter.api.cca.events.JCreditControlAnswer;
import org.jdiameter.api.cca.events.JCreditControlRequest;
import org.jdiameter.common.impl.DiameterUtilities;
import org.jdiameter.common.impl.app.cca.JCreditControlAnswerImpl;

import java.util.HashMap;
import java.util.Map;


//@DiameterService
@Slf4j
public class DemoService implements ServerCCASessionListener
{
	private final Map<String, Long> accounts = new HashMap<>();
	private final Map<String, Long> reserved = new HashMap<>();


	void onStart(@Observes StartupEvent ev)
	{
		LOG.info("Loading accounts");
		accounts.put("0828938386", 100L);
		accounts.put("012345", 100L);
	}

	private JCreditControlAnswer createCCA(ServerCCASession session, JCreditControlRequest request, long grantedUnits, long resultCode)
			throws InternalException, AvpDataException
	{
		JCreditControlAnswerImpl answer = new JCreditControlAnswerImpl((Request) request.getMessage(), resultCode);

		AvpSet ccrAvps = request.getMessage().getAvps();
		AvpSet ccaAvps = answer.getMessage().getAvps();
		// Using the same as the one present in request
		ccaAvps.addAvp(ccrAvps.getAvp(Avp.CC_REQUEST_TYPE));


		// Using the same as the one present in request
		ccaAvps.addAvp(ccrAvps.getAvp(Avp.CC_REQUEST_NUMBER));

		if (grantedUnits >= 0) {
			AvpSet gsuAvp = ccaAvps.addGroupedAvp(Avp.GRANTED_SERVICE_UNIT);
			// Fetch AVP/Value from Request
			// gsuAvp.addAvp(ccrAvps.getAvp(Avp.REQUESTED_SERVICE_UNIT).getGrouped().getAvp(Avp.CC_TIME));
			gsuAvp.addAvp(Avp.CC_TIME, grantedUnits, true);
		}

		LOG.info(">> Created Credit-Control-Answer.");

		return answer;
	}

	@Override
	public void doCreditControlRequest(ServerCCASession session, JCreditControlRequest request) throws InternalException, IllegalDiameterStateException, RouteException, OverloadException
	{
		long startTime = System.currentTimeMillis();
		DiameterUtilities.printMessage(request.getMessage());

		AvpSet ccrAvps = request.getMessage().getAvps();

		JCreditControlAnswer cca = null;
		switch (request.getRequestTypeAVPValue()) {

			case 1: // INITIAL_REQUEST
			case 2: // UPDATE_REQUEST
				LOG.info("<< Received Credit-Control-Request [" + (request.getRequestTypeAVPValue() == 1 ? "INITIAL" : "UPDATE") + "]");

				try {
					long requestedUnits = ccrAvps.getAvp(Avp.REQUESTED_SERVICE_UNIT).getGrouped().getAvp(Avp.CC_TIME).getInteger32();
					String subscriptionId = ccrAvps.getAvp(Avp.SUBSCRIPTION_ID).getGrouped().getAvp(Avp.SUBSCRIPTION_ID_DATA).getUTF8String();
					String serviceContextId = ccrAvps.getAvp(Avp.SERVICE_CONTEXT_ID).getUTF8String();

					LOG.info(">> '" + subscriptionId + "' requested " + requestedUnits + " units for '" + serviceContextId + "'.");

					Long balance = accounts.get(subscriptionId);
					if (balance != null) {
						if (balance <= 0) {
							// The credit-control server denies the service request because the
							// end user's account could not cover the requested service.  If the
							// CCR contained used-service-units they are deducted, if possible.
							cca = createCCA(session, request, -1, ResultCode.CREDIT_CONTROL_LIMIT_REACHED);
							LOG.info("<> '" + subscriptionId + "' has insufficient credit units. Rejecting.");
						} else {
							// Check if not first request, should have Used-Service-Unit AVP
							if (ccrAvps.getAvp(Avp.CC_REQUEST_NUMBER) != null && ccrAvps.getAvp(Avp.CC_REQUEST_NUMBER).getUnsigned32() >= 1) {
								Avp usedServiceUnit = ccrAvps.getAvp(Avp.USED_SERVICE_UNIT);
								if (usedServiceUnit != null) {
									Long wereReserved = reserved.remove(subscriptionId + "_" + serviceContextId);
									wereReserved = wereReserved == null ? 0 : wereReserved;
									long wereUsed = usedServiceUnit.getGrouped().getAvp(Avp.CC_TIME).getUnsigned32();
									long remaining = wereReserved - wereUsed;

									LOG.info(">> '" + subscriptionId + "' had " + wereReserved + " reserved units, " + wereUsed + " units were used."
									         + " (rem: " + remaining + ").");
									balance += remaining;
								}
							}

							long grantedUnits = Math.min(requestedUnits, balance);
							cca = createCCA(session, request, grantedUnits, ResultCode.SUCCESS);

							reserved.put(subscriptionId + "_" + serviceContextId, grantedUnits);
							balance -= grantedUnits;
							LOG.info(">> '" + subscriptionId + "' Balance: " + (balance + grantedUnits) +
							         " // Available(" + balance + ")  Reserved(" + grantedUnits + ")");
							accounts.put(subscriptionId, balance);

							// Check if the user has no more credit
							if (balance <= 0) {
								AvpSet finalUnitIndicationAvp = cca.getMessage().getAvps().addGroupedAvp(Avp.FINAL_UNIT_INDICATION);
								finalUnitIndicationAvp.addAvp(Avp.FINAL_UNIT_ACTION, 0); //TERMINATE
							}
						}
					} else {
						// The specified end user is unknown in the credit-control server.
						cca = createCCA(session, request, -1, 5030); //DIAMETER_USER_UNKNOWN
						cca.getMessage().setError(true);
						LOG.info("<> '" + subscriptionId + "' is not provisioned in this server. Rejecting.");
					}

					//cca.getMessage().getAvps().addAvp(Avp.SERVICE_CONTEXT_ID, serviceContextId, false);
					session.sendCreditControlAnswer(cca);
				}
				catch (Exception e) {
					LOG.error(">< Failure processing Credit-Control-Request [" + (request.getRequestTypeAVPValue() == 1 ? "INITIAL" : "UPDATE") + "]", e);
				}
				break;

			case 3: // TERMINATION_REQUEST
				LOG.info("<< Received Credit-Control-Request [TERMINATION]");
				try {
					String subscriptionId = ccrAvps.getAvp(Avp.SUBSCRIPTION_ID).getGrouped().getAvp(Avp.SUBSCRIPTION_ID_DATA).getUTF8String();
					String serviceContextId = ccrAvps.getAvp(Avp.SERVICE_CONTEXT_ID).getUTF8String();

					LOG.info(">> '" + subscriptionId + "' requested service termination for '" + serviceContextId + "'.");

					Long balance = accounts.get(subscriptionId);

					if (ccrAvps.getAvp(Avp.CC_REQUEST_NUMBER) != null && ccrAvps.getAvp(Avp.CC_REQUEST_NUMBER).getUnsigned32() >= 1) {
						Avp usedServiceUnit = ccrAvps.getAvp(Avp.USED_SERVICE_UNIT);
						if (usedServiceUnit != null) {
							long wereReserved = reserved.remove(subscriptionId + "_" + serviceContextId);
							long wereUsed = usedServiceUnit.getGrouped().getAvp(Avp.CC_TIME).getUnsigned32();
							long remaining = wereReserved - wereUsed;

							LOG.info(">> '" + subscriptionId + "' had " + wereReserved + " reserved units, " + wereUsed + " units were used."
							         + " (non-used: " + remaining + ").");
							balance += remaining;
						}
					}

					LOG.info(">> '" + subscriptionId + "' Balance: " + balance + " // Available(" + balance + ")  Reserved(0)");
					accounts.put(subscriptionId, balance);

					cca = createCCA(session, request, -1, ResultCode.SUCCESS);
					session.sendCreditControlAnswer(cca);
				}
				catch (Exception e) {
					LOG.error(">< Failure processing Credit-Control-Request [TERMINATION]", e);
				}
				break;
			// EVENT_REQUEST                   4
			case 4:
				LOG.info("<< Received Credit-Control-Request [EVENT]");
				break;
			default:
				break;
		}
		if (cca != null) {
			DiameterUtilities.printMessage(cca.getMessage());
		}
		LOG.debug("Answered in {}ms", System.currentTimeMillis() - startTime);
	}
}
